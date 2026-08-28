package com.deepseek.dsh.llm.adapter;

/**
 * 流式分块 —— 模型流式输出中的增量片段。
 *
 * <p>{@code delta} 为正文增量，{@code reasoningDelta} 为推理（reasoning_content）增量，
 * 二者可能同时存在（同一段 SSE chunk 可含 content + reasoning_content）。
 */
public record LlmChunk(
        /** 正文增量（可能为空或 null）。 */
        String delta,
        /** 推理增量（reasoning_content，可能为空或 null）。 */
        String reasoningDelta,
        /** 完成标志。 */
        boolean isDone
) {
    public static LlmChunk delta(String text) {
        return new LlmChunk(text, null, false);
    }

    public static LlmChunk reasoning(String text) {
        return new LlmChunk(null, text, false);
    }

    public static LlmChunk of(String content, String reasoning) {
        return new LlmChunk(content, reasoning, false);
    }

    public static LlmChunk done() {
        return new LlmChunk(null, null, true);
    }
}
