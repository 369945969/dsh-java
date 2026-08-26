package com.deepseek.dsh.tools.registry;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.exception.ToolException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolArgs 类型安全参数提取测试。
 */
class ToolArgsTest {

    @Test
    void 必填与可选字符串() {
        var args = new ToolArgs("t", Map.of("path", "/a/b", "mode", "r"));
        assertEquals("/a/b", args.requiredString("path"));
        assertEquals("r", args.optionalString("mode", "x"));
        assertEquals("def", args.optionalString("missing", "def"));
        assertThrows(ToolException.class, () -> args.requiredString("absent"));
    }

    @Test
    void 整数提取与类型不符抛异常() {
        var args = new ToolArgs("t", Map.of("offset", 5, "limit", 10, "str", "x"));
        assertEquals(5, args.requiredInt("offset"));
        assertEquals(10, args.optionalInt("limit", 0));
        assertThrows(ToolException.class, () -> args.requiredInt("no"));
        // 字符串值处期望整数 → 抛异常
        assertThrows(ToolException.class, () -> args.requiredInt("str"));
    }

    @Test
    void null参数按空map处理() {
        var args = new ToolArgs("t", null);
        assertEquals("d", args.optionalString("x", "d"));
        assertFalse(args.has("x"));
    }
}
