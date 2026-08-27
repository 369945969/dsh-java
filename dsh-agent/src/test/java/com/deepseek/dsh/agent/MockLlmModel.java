package com.deepseek.dsh.agent;

import java.util.List;
import java.util.concurrent.Flow;

import com.deepseek.dsh.llm.adapter.LlmChunk;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.adapter.LlmRequest;
import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 模型 Mock —— 用于测试 ReAct 循环，不实际调用 API。
 *
 * <p>可脚本化：按顺序返回预设的响应序列。
 * <p>设计模式：测试替身（Test Double）—— 桩（Stub）。
 */
public final class MockLlmModel implements LlmModel {

    private final java.util.Queue<LlmResponse> queue = new java.util.ArrayDeque<>();

    /** 入队一条响应。 */
    public MockLlmModel enqueue(LlmResponse response) {
        queue.add(response);
        return this;
    }

    /** 入队一条纯文本响应。 */
    public MockLlmModel enqueueText(String content) {
        queue.add(new LlmResponse(content, List.of(), null, "stop"));
        return this;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (queue.isEmpty()) {
            return new LlmResponse("(No more responses)", List.of(), null, "stop");
        }
        return queue.poll();
    }

    @Override
    public Flow.Publisher<LlmChunk> stream(LlmRequest request) {
        throw new UnsupportedOperationException("Streaming not supported in test");
    }
}
