package com.deepseek.dsh.storage.mysql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.SessionAuth;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.persistence.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MySQL 会话事件存储 —— SessionStore 的 DB 后端，写 session_event 表 + session 元数据表。
 * 与 JsonlSessionStore 同契约，DSH_STORAGE=mysql 时由 BaseBundle 装配。
 *
 * <p>append 时从 {@link SessionAuth}（当前请求线程上下文）取 appid/userid/reasoning/modelId，
 * 冗余到 session_event 行并 upsert 到 session 表（便于按应用/用户/模型统计/清理）。
 *
 * <p>设计模式：仓储（Repository）—— 事件存储后端，HikariCP 连接池复用。
 */
public final class MysqlSessionStore implements SessionStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MysqlSessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final MysqlStorage storage;

    public MysqlSessionStore(MysqlStorage storage) {
        this.storage = storage;
        ensureSchema();
    }

    private void ensureSchema() {
        try (Connection c = storage.borrow(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS `session` (
                        `session_id` VARCHAR(64) NOT NULL,
                        `appid` VARCHAR(64) NOT NULL DEFAULT 'default',
                        `userid` VARCHAR(64) NOT NULL DEFAULT '',
                        `reasoning` VARCHAR(8) NOT NULL DEFAULT 'auto',
                        `model_id` VARCHAR(64) NOT NULL DEFAULT '',
                        `workspace_id` VARCHAR(64) NOT NULL DEFAULT '',
                        `title` VARCHAR(256) NULL,
                        `cwd` VARCHAR(512) NULL,
                        `status` VARCHAR(16) NOT NULL DEFAULT 'active',
                        `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (`session_id`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS `session_event` (
                        `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                        `session_id` VARCHAR(64) NOT NULL,
                        `appid` VARCHAR(64) NOT NULL DEFAULT 'default',
                        `userid` VARCHAR(64) NOT NULL DEFAULT '',
                        `seq` INT NOT NULL,
                        `event_type` VARCHAR(64) NOT NULL,
                        `data` JSON NULL,
                        `surface_op` VARCHAR(32) NULL,
                        `lineage_parent_session` VARCHAR(64) NULL,
                        `lineage_depth` INT NOT NULL DEFAULT 0,
                        `reasoning` VARCHAR(8) NOT NULL DEFAULT 'auto',
                        `model_id` VARCHAR(64) NOT NULL DEFAULT '',
                        `workspace_id` VARCHAR(64) NOT NULL DEFAULT '',
                        `time` BIGINT UNSIGNED NOT NULL,
                        `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (`id`),
                        UNIQUE KEY `uk_session_seq` (`session_id`, `seq`),
                        KEY `idx_session_time` (`session_id`, `time`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            log.debug("MySQL session tables ensured");
        } catch (java.sql.SQLException e) {
            log.error("MysqlSessionStore ensureSchema failed: {}", e.toString());
        }
    }

    @Override
    public void append(SessionEvent event) throws IOException {
        String sid = event.sessionId().value();
        String appid = SessionAuth.appid();
        String userid = SessionAuth.userid();
        String reasoning = SessionAuth.reasoning();
        String modelId = SessionAuth.modelId();
        String workspaceId = SessionAuth.workspaceId();
        String dataJson;
        try {
            dataJson = MAPPER.writeValueAsString(event.data());
        } catch (Exception e) {
            dataJson = "{}";
        }
        String parent = null;
        int depth = 0;
        if (event.lineage() != null) {
            parent = event.lineage().parentSession() == null ? null : event.lineage().parentSession().value();
            depth = event.lineage().delegationDepth();
        }
        try (Connection c = storage.borrow()) {
            // upsert session 元数据
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `session` (session_id,appid,userid,reasoning,model_id,workspace_id,status) " +
                    "VALUES (?,?,?,?,?,?,'active') ON DUPLICATE KEY UPDATE " +
                    "appid=VALUES(appid),userid=VALUES(userid),reasoning=VALUES(reasoning)," +
                    "model_id=VALUES(model_id),workspace_id=VALUES(workspace_id)")) {
                ps.setString(1, sid); ps.setString(2, appid); ps.setString(3, userid);
                ps.setString(4, reasoning); ps.setString(5, modelId); ps.setString(6, workspaceId);
                ps.executeUpdate();
            }
            // insert event
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO `session_event` " +
                    "(session_id,appid,userid,seq,event_type,data,surface_op,lineage_parent_session,lineage_depth,reasoning,model_id,workspace_id,time) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, sid); ps.setString(2, appid); ps.setString(3, userid);
                ps.setInt(4, (int) event.seq());
                ps.setString(5, event.type());
                ps.setString(6, dataJson);
                ps.setString(7, event.surfaceOp());
                if (parent == null) ps.setNull(8, java.sql.Types.VARCHAR); else ps.setString(8, parent);
                ps.setInt(9, depth);
                ps.setString(10, reasoning); ps.setString(11, modelId); ps.setString(12, workspaceId);
                ps.setLong(13, event.time());
                ps.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("append session_event failed: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SessionEvent> load(SessionId sessionId) throws IOException {
        List<SessionEvent> out = new ArrayList<>();
        try (Connection c = storage.borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq,event_type,data,surface_op,lineage_parent_session,lineage_depth,time " +
                     "FROM `session_event` WHERE session_id=? ORDER BY seq")) {
            ps.setString(1, sessionId.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long seq = rs.getInt("seq");
                    String type = rs.getString("event_type");
                    String dataJson = rs.getString("data");
                    Map<String, Object> data = null;
                    if (dataJson != null && !dataJson.isBlank()) {
                        try { data = MAPPER.readValue(dataJson, Map.class); } catch (Exception ignored) {}
                    }
                    String surfaceOp = rs.getString("surface_op");
                    String parent = rs.getString("lineage_parent_session");
                    int depth = rs.getInt("lineage_depth");
                    long time = rs.getLong("time");
                    SessionEvent.Lineage lin = new SessionEvent.Lineage(
                            parent == null ? null : SessionId.of(parent), depth);
                    out.add(new SessionEvent(seq, sessionId, type, data == null ? Map.of() : data,
                            time, surfaceOp, lin));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("load session_event failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<SessionId> listAll() throws IOException {
        List<SessionId> ids = new ArrayList<>();
        try (Connection c = storage.borrow();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id FROM `session` WHERE status='active'")) {
            while (rs.next()) ids.add(SessionId.of(rs.getString(1)));
        } catch (java.sql.SQLException e) {
            throw new IOException("listAll sessions failed: " + e.getMessage(), e);
        }
        return ids;
    }

    @Override
    public boolean delete(SessionId sessionId) throws IOException {
        try (Connection c = storage.borrow()) {
            // session 删了，session_event 由外键 CASCADE 删除；无 FK 时手动删
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM `session_event` WHERE session_id=?")) {
                ps.setString(1, sessionId.value()); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM `session` WHERE session_id=?")) {
                ps.setString(1, sessionId.value());
                n = ps.executeUpdate();
            }
            return n > 0;
        } catch (java.sql.SQLException e) {
            throw new IOException("delete session failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // pool 由 MysqlStorage 持有，由其关闭
    }
}
