package com.deepseek.dsh.storage.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.SessionAuth;
import com.deepseek.dsh.skill.SkillDefinition;
import com.deepseek.dsh.skill.SkillInvocationPolicy;
import com.deepseek.dsh.skill.SkillProvider;

/**
 * MySQL 技能提供者 —— 从 app_skill 表加载技能（按 appid 区分）。
 * DSH_STORAGE=mysql 时由 BaseBundle 注册，替代/叠加 FilesystemSkillProvider。
 * locator 用 skill_name（同 appid 下唯一），get() 时按 name 回查正文。
 */
public final class MysqlSkillProvider implements SkillProvider {

    private static final Logger log = LoggerFactory.getLogger(MysqlSkillProvider.class);
    private final MysqlStorage storage;

    public MysqlSkillProvider(MysqlStorage storage) {
        this.storage = storage;
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public List<SkillCandidate> list(String cwd) {
        String appid = SessionAuth.appid();
        List<SkillCandidate> out = new ArrayList<>();
        try (Connection c = storage.borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT skill_name,description,when_to_use,model_invocable,user_invocable " +
                     "FROM app_skill WHERE appid=? AND enabled=1")) {
            ps.setString(1, appid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("skill_name");
                    String desc = rs.getString("description");
                    String when = rs.getString("when_to_use");
                    SkillInvocationPolicy inv = new SkillInvocationPolicy(
                            rs.getBoolean("model_invocable"), rs.getBoolean("user_invocable"));
                    out.add(new SkillCandidate(
                            name, desc == null ? "" : desc,
                            when == null || when.isBlank() ? Optional.empty() : Optional.of(when),
                            inv, "mysql", name(), Optional.empty(), Optional.empty(), 500, name));
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("MysqlSkillProvider list (appid={}) failed: {}", appid, e.toString());
        }
        return out;
    }

    @Override
    public Optional<SkillDefinition> get(SkillCandidate candidate) {
        String appid = SessionAuth.appid();
        try (Connection c = storage.borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT skill_name,description,when_to_use,content,model_invocable,user_invocable " +
                     "FROM app_skill WHERE appid=? AND skill_name=? AND enabled=1")) {
            ps.setString(1, appid);
            ps.setString(2, candidate.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String when = rs.getString("when_to_use");
                    SkillInvocationPolicy inv = new SkillInvocationPolicy(
                            rs.getBoolean("model_invocable"), rs.getBoolean("user_invocable"));
                    return Optional.of(new SkillDefinition(
                            rs.getString("skill_name"),
                            rs.getString("description"),
                            when == null || when.isBlank() ? Optional.empty() : Optional.of(when),
                            inv, "mysql", name(), Optional.empty(), Optional.empty(),
                            rs.getString("content")));
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("MysqlSkillProvider get (appid={}, name={}) failed: {}", appid, candidate.name(), e.toString());
        }
        return Optional.empty();
    }
}
