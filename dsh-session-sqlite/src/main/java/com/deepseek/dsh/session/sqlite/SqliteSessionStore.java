package com.deepseek.dsh.session.sqlite;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.persistence.SessionStore;
import com.deepseek.dsh.session.log.SessionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SQLite 持久化后端 —— 将 wire 格式 SessionEvent 存入 SQLite，
 * data 字段以 JSON 文本存储，type 为 wire 事件类型字符串。
 */
public class SqliteSessionStore implements SessionStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final java.sql.Connection connection;

    public SqliteSessionStore(java.nio.file.Path dbPath) throws IOException {
        try {
            String url = "jdbc:sqlite:" + dbPath.toString();
            connection = java.sql.DriverManager.getConnection(url);
            initSchema();
        } catch (java.sql.SQLException e) {
            throw new IOException("Failed to open SQLite: " + e.getMessage(), e);
        }
    }

    private void initSchema() throws java.sql.SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS events (
                        seq INTEGER NOT NULL,
                        session_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        data_json TEXT NOT NULL DEFAULT '{}',
                        time INTEGER NOT NULL DEFAULT 0,
                        surface_op TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (session_id, seq)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS events_fts (
                        rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL,
                        text TEXT NOT NULL,
                        content_type TEXT NOT NULL
                    )
                    """);
            log.debug("SQLite session table initialized");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void append(SessionEvent event) throws IOException {
        try {
            String dataJson = MAPPER.writeValueAsString(event.data());
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO events (seq, session_id, type, data_json, time, surface_op, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, event.seq());
                ps.setString(2, event.sessionId().value());
                ps.setString(3, event.type());
                ps.setString(4, dataJson);
                ps.setLong(5, event.time());
                ps.setString(6, event.surfaceOp());
                ps.setString(7, java.time.Instant.now().toString());
                ps.executeUpdate();
            }
            String text = extractText(event);
            if (text != null && !text.isBlank()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO events_fts (session_id, text, content_type) VALUES (?, ?, ?)")) {
                    ps.setString(1, event.sessionId().value());
                    ps.setString(2, text);
                    ps.setString(3, event.type());
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new IOException("写入事件失败: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SessionEvent> load(SessionId sessionId) throws IOException {
        List<SessionEvent> out = new ArrayList<>();
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT seq, type, data_json, time, surface_op, created_at FROM events WHERE session_id = ? ORDER BY seq")) {
                ps.setString(1, sessionId.value());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long seq = rs.getLong("seq");
                        String type = rs.getString("type");
                        String dataJson = rs.getString("data_json");
                        long time = rs.getLong("time");
                        String surfaceOp = rs.getString("surface_op");
                        java.util.Map<String, Object> data = dataJson != null
                                ? MAPPER.readValue(dataJson, java.util.Map.class)
                                : java.util.Map.of();
                        out.add(new SessionEvent(seq, sessionId, type, data, time, surfaceOp));
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("加载事件失败: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<SessionId> listAll() throws IOException {
        List<SessionId> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT session_id FROM events")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(SessionId.of(rs.getString("session_id")));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("列出会话失败: " + e.getMessage(), e);
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static String extractText(SessionEvent e) {
        if ("user/message".equals(e.type()) || "assistant/message".equals(e.type())) {
            Object content = e.data().get("content");
            if (content instanceof List<?> parts) {
                StringBuilder sb = new StringBuilder();
                for (Object part : parts) {
                    if (part instanceof java.util.Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t) {
                        sb.append(t);
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (java.sql.SQLException e) {
            log.warn("Failed to close SQLite: {}", e.getMessage());
        }
    }
}
