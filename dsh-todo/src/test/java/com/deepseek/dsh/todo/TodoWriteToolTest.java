package com.deepseek.dsh.todo;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.tools.registry.ToolContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * todo_write 工具测试。
 */
class TodoWriteToolTest {

    @Test
    void 写入并读取任务清单() throws Exception {
        var tool = new TodoWriteTool();
        var ctx = new ToolContext(SessionId.of("test-1"), null, null);
        var args = java.util.Map.<String, Object>of("todos", java.util.List.<java.util.Map<String, Object>>of(
                java.util.Map.of("content", "任务一", "status", "in_progress", "priority", "high"),
                java.util.Map.of("content", "任务二", "status", "pending", "priority", "low")));
        String result = tool.invoke(args, ctx);
        assertTrue(result.contains("任务一"));
        assertTrue(result.contains("IN_PROGRESS"));
        assertEquals(2, tool.currentTodos(SessionId.of("test-1")).size());
    }
}
