package com.deepseek.dsh.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * Todo 列表项 —— 对应原 Harness 的 todo_write 状态。
 */
public record TodoItem(
        /** 内容。 */
        String content,
        /** 状态。 */
        Status status,
        /** 优先级。 */
        String priority
) {
    public enum Status { PENDING, IN_PROGRESS, COMPLETED }

    public static TodoItem pending(String content, String priority) {
        return new TodoItem(content, Status.PENDING, priority != null ? priority : "medium");
    }
}
