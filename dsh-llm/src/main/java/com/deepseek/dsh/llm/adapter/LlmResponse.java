package com.deepseek.dsh.llm.adapter;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * LLM 响应 —— 模型返回的完整结果。
 *
 * @param content      助手文本内容（最终回复）。
 * @param reasoning    推理/思考内容（reasoning 模型的 reasoning_content；普通模型为空）。
 * @param toolCalls    模型发起的工具调用（若有）。
 * @param usage        本次使用的 token 用量统计。
 * @param finishReason 结束原因：stop / tool_calls / length 等。
 */
public record LlmResponse(
        String content,
        String reasoning,
        List<ChatMessage.ToolCall> toolCalls,
        TokenUsage usage,
        String finishReason
) {
    /** 兼容无 reasoning 的旧调用（reasoning 留空）。 */
    public LlmResponse(String content, List<ChatMessage.ToolCall> toolCalls, TokenUsage usage, String finishReason) {
        this(content, "", toolCalls, usage, finishReason);
    }

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
}
