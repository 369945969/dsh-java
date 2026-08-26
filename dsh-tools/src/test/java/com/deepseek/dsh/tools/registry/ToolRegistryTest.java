package com.deepseek.dsh.tools.registry;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.tools.schema.ToolSchema;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具注册表测试 —— 注册/查询/schema/重复注册/注销。
 */
class ToolRegistryTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override public ToolSchema schema() {
                return ToolSchema.of(name, "测试工具 " + name, Map.of("type", "object", "properties", Map.of(), "required", java.util.List.of()));
            }
            @Override public String invoke(Map<String, Object> arguments, ToolContext ctx) {
                return "ok-" + name;
            }
        };
    }

    @Test
    void 注册查询与schema() {
        var reg = new ToolRegistry();
        reg.register(tool("read"));
        reg.register(tool("write"));
        assertEquals(2, reg.all().size());
        assertTrue(reg.get("read").isPresent());
        assertEquals(2, reg.schemas().size());
        assertTrue(reg.names().contains("write"));
    }

    @Test
    void 重复注册抛异常() {
        var reg = new ToolRegistry();
        reg.register(tool("bash"));
        assertThrows(IllegalStateException.class, () -> reg.register(tool("bash")));
    }

    @Test
    void 注销后不可查() {
        var reg = new ToolRegistry();
        var d = reg.register(tool("grep"));
        assertTrue(reg.get("grep").isPresent());
        d.dispose();
        assertTrue(reg.get("grep").isEmpty());
        // 二次注销幂等
        d.dispose();
    }

    @Test
    void 未知名返回空() {
        var reg = new ToolRegistry();
        assertTrue(reg.get("nope").isEmpty());
        assertTrue(reg.names().isEmpty());
    }
}
