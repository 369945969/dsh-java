package com.deepseek.dsh.llm.adapter;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * LLM 模型适配器能力缝 —— 对应原 Harness 的 {@code ctx.llm}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code DeepSeekLlmAdapter}（{@code dsh-llm-deepseek} 等价物）。</li>
 *   <li><b>消费者</b>：agent loop 通过 {@code ctx.require(LlmModel.class)} 获取。</li>
 * </ul>
 *
 * <p>设计模式：策略（可互换模型提供者）+ 适配器（统一不同厂商 API）。
 */
public interface LlmModel {

    /**
     * 一次性（非流式）调用模型。
     *
     * @param request 完整请求
     * @return 完整响应
     */
    LlmResponse chat(LlmRequest request) throws Exception;

    /**
     * 流式调用模型，返回分块的 {@link Flow.Publisher}。
     *
     * @param request 完整请求
     * @return 分块发布者
     */
    Flow.Publisher<LlmChunk> stream(LlmRequest request) throws Exception;

    /**
     * 流式调用并收集：逐 chunk 经 {@code onChunk} 推送增量（正文 {@link LlmChunk#delta}
     * 与推理 {@link LlmChunk#reasoningDelta}），同时累积完整响应（含工具调用），流结束后返回
     * 完整 {@link LlmResponse}。默认实现回退到 {@link #chat}（不推送增量），供不支持流式的模型复用。
     *
     * @param request 完整请求
     * @param onChunk 每个增量分块的回调（可为 null）
     * @return 累积后的完整响应
     */
    default LlmResponse streamCollect(LlmRequest request, Consumer<LlmChunk> onChunk) throws Exception {
        return chat(request);
    }
}
