package com.deepseek.dsh.llm.adapter;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * LLM 请求 —— 发往模型的完整请求负载。
 */
public record LlmRequest(
        /** 对话历史（含系统提示）。 */
        List<ChatMessage> messages,
        /** 可用工具 schema。 */
        List<ToolSchema> tools,
        /** 模型标识（如 "deepseek-chat"）。 */
        String model,
        /** 采样温度。 */
        Double temperature,
        /** 最大输出 token。 */
        Integer maxTokens
) {
    public static LlmRequest of(List<ChatMessage> messages, List<ToolSchema> tools, String model) {
        return new LlmRequest(messages, tools, model, null, null);
    }
}
