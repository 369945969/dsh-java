package com.deepseek.dsh.storage.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.llm.config.ModelProfileBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MySQL 模型档案后端 —— 从 model_profile 表读写 {activeId, profiles:[...]}。
 * 默认查 appid='default'（全局档案，对齐 model-config.json 单实例语义）。
 * DSH_STORAGE=mysql 时由 BaseBundle 装配，替代 FileModelProfileBackend。
 */
public final class MysqlModelProfileBackend implements ModelProfileBackend {

    private static final Logger log = LoggerFactory.getLogger(MysqlModelProfileBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MysqlStorage storage;
    private final String appid;

    public MysqlModelProfileBackend(MysqlStorage storage) {
        this(storage, "default");
    }

    public MysqlModelProfileBackend(MysqlStorage storage, String appid) {
        this.storage = storage;
        this.appid = appid;
    }

    @Override
    public JsonNode load() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("profiles");
        String activeId = null;
        try (Connection c = storage.borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,display_name,api_key,base_url,model,route,models,is_active " +
                     "FROM model_profile WHERE appid=? ORDER BY is_active DESC, sort_order")) {
            ps.setString(1, appid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode o = arr.addObject();
                    o.put("id", rs.getString("id"));
                    o.put("displayName", rs.getString("display_name"));
                    o.put("apiKey", rs.getString("api_key"));
                    o.put("baseUrl", rs.getString("base_url"));
                    o.put("model", rs.getString("model"));
                    String route = rs.getString("route");
                    if (route != null && !route.isBlank()) o.put("route", route);
                    String modelsJson = rs.getString("models");
                    if (modelsJson != null && !modelsJson.isBlank()) {
                        try { o.set("models", MAPPER.readTree(modelsJson)); } catch (Exception ignored) {}
                    }
                    if (rs.getBoolean("is_active")) activeId = rs.getString("id");
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("MysqlModelProfileBackend load failed: {}", e.toString());
            return null;
        }
        root.put("activeId", activeId == null ? "" : activeId);
        log.info("Loaded {} model profile(s) from DB (appid={})", arr.size(), appid);
        return root;
    }

    @Override
    public void persist(JsonNode root) {
        if (root == null) return;
        String activeId = root.path("activeId").asText("");
        var profiles = root.path("profiles");
        if (!profiles.isArray()) return;
        try (Connection c = storage.borrow()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM model_profile WHERE appid=?")) {
                del.setString(1, appid); del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO model_profile (id,appid,display_name,api_key,base_url,model,route,models,is_active,sort_order) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                int order = 0;
                for (JsonNode p : profiles) {
                    String id = p.path("id").asText();
                    ps.setString(1, id);
                    ps.setString(2, appid);
                    ps.setString(3, p.path("displayName").asText(""));
                    ps.setString(4, p.path("apiKey").asText(""));
                    ps.setString(5, p.path("baseUrl").asText(""));
                    ps.setString(6, p.path("model").asText(""));
                    ps.setString(7, p.path("route").asText(""));
                    JsonNode models = p.path("models");
                    if (models.isArray() && !models.isEmpty()) {
                        try { ps.setString(8, MAPPER.writeValueAsString(models)); }
                        catch (Exception je) { ps.setNull(8, java.sql.Types.VARCHAR); }
                    } else ps.setNull(8, java.sql.Types.VARCHAR);
                    ps.setBoolean(9, id.equals(activeId));
                    ps.setInt(10, order++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
        } catch (java.sql.SQLException e) {
            log.warn("MysqlModelProfileBackend persist failed: {}", e.toString());
        }
    }
}
