package com.deepseek.dsh.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.tools.registry.Tool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * todo_write 工具 —— 对应原 Harness 的 {@code tool-todo}。
 *
 * <p>管理任务清单：模型用此工具规划、跟踪任务进度。状态按会话隔离。
 * 因参数含数组，直接实现 {@link Tool}（不用 AbstractTool 的 ToolArgs）。
 *
 * <p>设计模式：命令（Command）。
 */
public final class TodoWriteTool implements Tool {

    private final ConcurrentMap<SessionId, List<TodoItem>> todos = new ConcurrentHashMap<>();

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("todo_write", "管理任务清单（规划与跟踪进度）。", Map.of(
                "type", "object",
                "properties", Map.of(
                        "todos", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of("type", "string", "description", "任务内容"),
                                                "status", Map.of("type", "string", "enum",
                                                        List.of("pending", "in_progress", "completed"),
                                                        "description", "状态"),
                                                "priority", Map.of("type", "string",
                                                        "description", "优先级（high/medium/low）")),
                                        "required", List.of("content", "status")),
                                "description", "任务列表（完整替换）")
                ),
                "required", List.of("todos")
        ));
    }

    @Override
    @SuppressWarnings("unchecked")
    public String invoke(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        List<Map<String, Object>> rawTodos = (List<Map<String, Object>>) arguments.get("todos");
        if (rawTodos == null) return "（无任务）";

        List<TodoItem> items = new ArrayList<>();
        for (Map<String, Object> raw : rawTodos) {
            String content = (String) raw.get("content");
            String statusStr = (String) raw.getOrDefault("status", "pending");
            String priority = (String) raw.getOrDefault("priority", "medium");
            TodoItem.Status status = TodoItem.Status.valueOf(statusStr.toUpperCase());
            items.add(new TodoItem(content, status, priority));
        }
        todos.put(ctx.sessionId(), items);

        StringBuilder sb = new StringBuilder("已更新任务清单:\n");
        for (int i = 0; i < items.size(); i++) {
            TodoItem t = items.get(i);
            sb.append(String.format("  %d. [%s] %s (%s)\n",
                    i + 1, t.status(), t.content(), t.priority()));
        }
        return sb.toString().trim();
    }

    /** 获取某会话的当前任务清单。 */
    public List<TodoItem> currentTodos(SessionId sessionId) {
        return todos.getOrDefault(sessionId, List.of());
    }
}
