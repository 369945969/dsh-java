package com.deepseek.dsh.workflow;

/**
 * 工作流结果。
 */
public record WorkflowResult(
        String taskId,
        String output,
        boolean success
) {
    public static WorkflowResult ok(String taskId, String output) {
        return new WorkflowResult(taskId, output, true);
    }

    public static WorkflowResult failed(String taskId, String reason) {
        return new WorkflowResult(taskId, reason, false);
    }
}
