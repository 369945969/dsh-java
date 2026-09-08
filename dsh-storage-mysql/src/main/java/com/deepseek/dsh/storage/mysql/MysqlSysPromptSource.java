package com.deepseek.dsh.storage.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.SessionAuth;

/**
 * MySQL 系统提示词 + 上下文源 —— 从 sys_prompt 表（全 type：system/instruction/context/custom，
 * enabled=1，priority 升序）+ app_context 表（全 ctx_type，enabled=1）读出，拼成注入文本。
 * DSH_STORAGE=mysql 时由 BaseBundle 调用，前置叠加在静态系统提示词之上。
 */
public final class MysqlSysPromptSource {

    private static final Logger log = LoggerFactory.getLogger(MysqlSysPromptSource.class);

    private final MysqlStorage storage;

    public MysqlSysPromptSource(MysqlStorage storage) {
        this.storage = storage;
    }

    /** 读当前 appid 的系统/指令/上下文提示词块（sys_prompt 全 type，priority 升序）+ app_context 块。 */
    public String loadSystemPrompt() {
        String appid = SessionAuth.appid();
        StringBuilder sb = new StringBuilder();
        // 1) sys_prompt 全 type（system/instruction/context/custom）
        try (Connection c = storage.borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name,content FROM sys_prompt " +
                     "WHERE appid=? AND enabled=1 ORDER BY priority ASC")) {
            ps.setString(1, appid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String content = rs.getString("content");
                    if (content == null || content.isBlank()) continue;
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(content);
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("MysqlSysPromptSource sys_prompt (appid={}) failed: {}", appid, e.toString());
        }
        // 2) app_context 全 ctx_type（数据型上下文）
        try (Connection c = storage.borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ctx_type,ctx_key,content FROM app_context " +
                     "WHERE appid=? AND enabled=1 ORDER BY ctx_type,ctx_key")) {
            ps.setString(1, appid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String content = rs.getString("content");
                    if (content == null || content.isBlank()) continue;
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(content);
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("MysqlSysPromptSource app_context (appid={}) failed: {}", appid, e.toString());
        }
        log.debug("Loaded sys_prompt+context {} chars (appid={})", sb.length(), appid);
        return sb.toString();
    }
}
