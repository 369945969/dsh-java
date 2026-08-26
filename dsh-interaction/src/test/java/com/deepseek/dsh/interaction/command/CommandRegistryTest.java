package com.deepseek.dsh.interaction.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令注册表测试 —— 注册/查询/重复注册/注销/幂等。
 */
class CommandRegistryTest {

    @Test
    void 注册与查询命令() {
        var reg = new CommandRegistry();
        reg.register("goal", args -> "goal-" + args.length);
        assertTrue(reg.get("goal").isPresent());
        assertEquals("goal-2", reg.get("goal").orElseThrow().handle(new String[]{"a", "b"}));
    }

    @Test
    void 重复注册抛异常() {
        var reg = new CommandRegistry();
        reg.register("compact", args -> "ok");
        assertThrows(IllegalStateException.class,
                () -> reg.register("compact", args -> "dup"));
    }

    @Test
    void 注销后不可查且幂等() {
        var reg = new CommandRegistry();
        var d = reg.register("clear", args -> "cleared");
        assertTrue(reg.get("clear").isPresent());
        d.dispose();
        assertTrue(reg.get("clear").isEmpty());
        // 二次注销幂等
        d.dispose();
    }

    @Test
    void 未知名返回空() {
        var reg = new CommandRegistry();
        assertTrue(reg.get("nope").isEmpty());
    }
}
