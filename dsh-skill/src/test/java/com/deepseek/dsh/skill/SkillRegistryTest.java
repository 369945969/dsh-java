package com.deepseek.dsh.skill;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能注册表测试 —— 运行时注册 + 列表 + 加载 + 名校验。
 */
class SkillRegistryTest {

    @Test
    void 运行时注册与列表查询() {
        var reg = new SkillRegistry();
        reg.register(SkillRegistration.of("deploy-app", "部署应用", "步骤说明"));
        var summaries = reg.list(null);
        assertEquals(1, summaries.size());
        assertEquals("deploy-app", summaries.get(0).name());
    }

    @Test
    void 按名加载完整定义() {
        var reg = new SkillRegistry();
        reg.register(SkillRegistration.of("cleanup", "清理", "body here"));
        var def = reg.get("cleanup", null).orElseThrow();
        assertEquals("body here", def.content());
        assertEquals("runtime", def.provider());
        assertTrue(def.invocation().modelInvocable());
    }

    @Test
    void 非法名拒绝注册() {
        var reg = new SkillRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> reg.register(SkillRegistration.of("Bad_Name", "x", "y")));
    }

    @Test
    void 未知名返回空() {
        var reg = new SkillRegistry();
        assertTrue(reg.get("nope", null).isEmpty());
    }

    @Test
    void 名校验() {
        assertTrue(SkillService.isSkillName("a-b-1"));
        assertFalse(SkillService.isSkillName("A-B"));
        assertFalse(SkillService.isSkillName("a b"));
    }
}
