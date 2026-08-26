package com.deepseek.dsh.session.log;

/**
 * 模型对话消息 —— 从 {@link SessionEvent} 日志投影得到。
 *
 * <p>角色对齐 OpenAI/DeepSeek Chat Completions 的 message role 约定。
 */
public record ChatMessage(
        Role role,
        String content,
        /** 当 role=tool 时的工具调用 ID。 */
        String toolCallId,
        /** 当 role=assistant 且发起工具调用时的调用列表。 */
        java.util.List<ToolCall> toolCalls
) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public record ToolCall(
            String id,
            String name,
            String argumentsJson
    ) {}

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, java.util.List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, java.util.List.of());
    }

    public static ChatMessage assistant(String content, java.util.List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, null, toolCalls);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, toolCallId, java.util.List.of());
    }
}
