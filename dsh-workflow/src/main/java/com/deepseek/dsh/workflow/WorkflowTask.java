package com.deepseek.dsh.workflow;

import java.util.concurrent.CompletableFuture;

/**
 * 工作流任务 —— 提交给工作流引擎的异步工作单元。
 */
public record WorkflowTask(
        /** 任务 ID。 */
        String id,
        /** 任务描述。 */
        String description,
        /** 任务负载（参数）。 */
        String payload
) {
    public static WorkflowTask of(String id, String description, String payload) {
        return new WorkflowTask(id, description, payload);
    }
}
