package com.deepseek.dsh.core.exception;

/**
 * 工具执行异常 —— 工具调用失败时抛出。
 *
 * <p>携带工具名与调用 ID，便于在会话日志中关联失败的工具调用。
 *
 * <p>可恢复性：参数错误不可恢复；超时/环境临时问题可恢复。
 */
public class ToolException extends DshException {

    private static final long serialVersionUID = 1L;

    private final String toolName;
    private final String toolCallId;

    public ToolException(String toolName, String message) {
        this(toolName, null, message, null, false);
    }

    public ToolException(String toolName, String message, Throwable cause) {
        this(toolName, null, message, cause, false);
    }

    public ToolException(String toolName, String toolCallId, String message,
                         Throwable cause, boolean recoverable) {
        super("tool." + toolName, toolCallId, message, cause, recoverable);
        this.toolName = toolName;
        this.toolCallId = toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    public String toolCallId() {
        return toolCallId;
    }
}
