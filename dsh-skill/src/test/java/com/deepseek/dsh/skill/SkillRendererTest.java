package com.deepseek.dsh.skill;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能渲染器测试 —— skill_content 块形态。
 */
class SkillRendererTest {

    @Test
    void 渲染标准块() {
        var def = new SkillDefinition(
                "deploy", "部署", Optional.of("需要时"),
                SkillInvocationPolicy.defaultPolicy(), "runtime", "filesystem",
                Optional.of("/tmp/skills"), Optional.of("/tmp/deploy.md"),
                "步骤一\n步骤二");
        String rendered = SkillRenderer.render(def);
        assertTrue(rendered.startsWith("<skill_content name=\"deploy\">"));
        assertTrue(rendered.contains("<skill_instructions>"));
        assertTrue(rendered.contains("步骤一"));
        assertTrue(rendered.contains("基址: /tmp/skills"));
        assertTrue(rendered.trim().endsWith("</skill_content>"));
    }

    @Test
    void 属性转义防注入() {
        var def = new SkillDefinition(
                "x", "d", Optional.empty(),
                SkillInvocationPolicy.defaultPolicy(), "runtime", "a\"&<provider",
                Optional.empty(), Optional.empty(), "body");
        String rendered = SkillRenderer.render(def);
        // 原始引号/尖括号不应出现在资源指引的提供者名中
        assertFalse(rendered.contains("a\"&<provider"));
    }
}
