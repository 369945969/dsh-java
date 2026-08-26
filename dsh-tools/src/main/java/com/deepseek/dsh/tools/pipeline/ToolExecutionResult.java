package com.deepseek.dsh.tools.pipeline;

/**
 * 工具执行结果。
 */
public record ToolExecutionResult(
        String toolCallId,
        String text,
        boolean isError
) {
    public static ToolExecutionResult ok(String toolCallId, String text) {
        return new ToolExecutionResult(toolCallId, text, false);
    }

    public static ToolExecutionResult error(String toolCallId, String message) {
        return new ToolExecutionResult(toolCallId, message, true);
    }
}
