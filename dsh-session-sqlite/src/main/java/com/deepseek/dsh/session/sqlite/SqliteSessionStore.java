package com.deepseek.dsh.session.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.persistence.SessionStore;

/**
 * SQLite 会话持久化 —— 对应原 Harness 的 {@code session-persistence-sqlite}。
 *
 * <p>用 SQLite 表存储事件，并建立 FTS5 全文索引以支持语义/全文会话查询。
 * 与 JSONL 相比，SQLite 支持高效检索与增量查询。
 *
 * <p>设计模式：仓储（Repository）—— 事件存储的可插拔后端。
 */
public final class SqliteSessionStore implements SessionStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionStore.class);

    private final Connection connection;

    public SqliteSessionStore(String dbPath) throws java.io.IOException {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException("无法打开 SQLite: " + e.getMessage(), e);
        }
        initSchema();
    }

    private void initSchema() throws java.io.IOException {
        try (Statement st = connection.createStatement()) {
            // 事件表
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS events (
                        seq INTEGER,
                        session_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        text TEXT,
                        tool_name TEXT,
                        tool_call_id TEXT,
                        created_at TEXT,
                        PRIMARY KEY (session_id, seq)
                    )
                    """);
            // FTS5 全文索引（对文本内容建虚拟表）
            st.executeUpdate("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(
                        session_id, text, content_type, tokenize='unicode61'
                    )
                    """);
            log.debug("SQLite session table initialized");
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException("Failed to initialize SQLite table: " + e.getMessage(), e);
        }
    }

    @Override
    public void append(SessionEvent event) throws java.io.IOException {
        String text = event.payload().text();
        String toolName = event.payload().toolName();
        String toolCallId = event.payload().toolCallId();
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO events (seq, session_id, type, text, tool_name, tool_call_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, event.seq());
                ps.setString(2, event.sessionId().value());
                ps.setString(3, event.type().name());
                ps.setString(4, text);
                ps.setString(5, toolName);
                ps.setString(6, toolCallId);
                ps.setString(7, event.createdAt().toString());
                ps.executeUpdate();
            }
            // 写入 FTS 索引（仅对有文本的事件）
            if (text != null && !text.isBlank()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO events_fts (session_id, text, content_type) VALUES (?, ?, ?)")) {
                    ps.setString(1, event.sessionId().value());
                    ps.setString(2, text);
                    ps.setString(3, event.type().name());
                    ps.executeUpdate();
                }
            }
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException("写入事件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SessionEvent> load(SessionId sessionId) throws java.io.IOException {
        List<SessionEvent> out = new ArrayList<>();
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT seq, type, text, tool_name, tool_call_id, created_at FROM events WHERE session_id = ? ORDER BY seq")) {
                ps.setString(1, sessionId.value());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long seq = rs.getLong("seq");
                        SessionEvent.Type type = SessionEvent.Type.valueOf(rs.getString("type"));
                        String text = rs.getString("text");
                        String toolName = rs.getString("tool_name");
                        String toolCallId = rs.getString("tool_call_id");
                        String createdAt = rs.getString("created_at");
                        SessionEvent.Payload payload = new SessionEvent.Payload(
                                text, java.util.Map.of(), toolName, toolCallId);
                        out.add(new SessionEvent(seq, sessionId, type, payload,
                                java.time.Instant.parse(createdAt), SessionEvent.Lineage.root()));
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException("加载会话失败: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * FTS 全文检索 —— 查询包含关键词的事件。
     *
     * @param query    FTS5 查询表达式（如 'error OR timeout'）
     * @param limit    最多返回条数
     * @return 匹配的 (sessionId, text, type) 三元组列表
     */
    public List<SearchHit> search(String query, int limit) throws java.io.IOException {
        List<SearchHit> hits = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT session_id, text, content_type FROM events_fts WHERE events_fts MATCH ? ORDER BY rank LIMIT ?")) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new SearchHit(
                            rs.getString("session_id"),
                            rs.getString("text"),
                            rs.getString("content_type")));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException("FTS 检索失败: " + e.getMessage(), e);
        }
        return hits;
    }

    /** 搜索命中结果。 */
    public record SearchHit(String sessionId, String text, String contentType) {}

    @Override
    public void close() throws java.io.IOException {
        try {
            connection.close();
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException("关闭 SQLite 失败: " + e.getMessage(), e);
        }
    }
}
