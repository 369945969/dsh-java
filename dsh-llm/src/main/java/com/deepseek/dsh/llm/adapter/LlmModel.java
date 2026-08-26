package com.deepseek.dsh.llm.adapter;

import java.util.concurrent.Flow;

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
}
