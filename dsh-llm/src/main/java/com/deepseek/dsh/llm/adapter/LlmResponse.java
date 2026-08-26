package com.deepseek.dsh.llm.adapter;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * LLM 响应 —— 模型返回的完整结果。
 */
public record LlmResponse(
        /** 助手文本内容。 */
        String content,
        /** 模型发起的工具调用（若有）。 */
        List<ChatMessage.ToolCall> toolCalls,
        /** 本次使用的 token 用量统计。 */
        TokenUsage usage,
        /** 结束原因：stop / tool_calls / length 等。 */
        String finishReason
) {
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
}
