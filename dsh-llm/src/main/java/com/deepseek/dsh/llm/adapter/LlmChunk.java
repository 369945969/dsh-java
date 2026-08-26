package com.deepseek.dsh.llm.adapter;

/**
 * 流式分块 —— 模型流式输出中的增量片段。
 */
public record LlmChunk(
        /** 增量文本（可能为空）。 */
        String delta,
        /** 完成标志。 */
        boolean isDone
) {
    public static LlmChunk delta(String text) {
        return new LlmChunk(text, false);
    }

    public static LlmChunk done() {
        return new LlmChunk(null, true);
    }
}
