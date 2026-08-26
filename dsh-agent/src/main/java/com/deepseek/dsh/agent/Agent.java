package com.deepseek.dsh.agent;

import java.util.List;
import java.util.Map;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.session.log.ChatMessage;

/**
 * Agent —— 一个可推理、可行动的 LLM 代理。对应原 Harness 的 {@code Agent} 接口。
 *
 * <p>agent 由一组可组合的构建块构成（借鉴 AgentScope 的 SDK 层）：
 * <ul>
 *   <li><b>模型</b>（{@link com.deepseek.dsh.llm.adapter.LlmModel}）—— 提供推理。</li>
 *   <li><b>工具包</b>（{@link com.deepseek.dsh.tools.registry.Tools}）—— 提供行动。</li>
 *   <li><b>中间件</b>（{@link com.deepseek.dsh.core.middleware.Middleware}）—— 贯穿循环的可组合钩子。</li>
 * </ul>
 *
 * <p>设计模式：策略（不同 agent 配置即不同策略组合）。
 */
public interface Agent {

    /** agent 名称/人格。 */
    String name();

    /** 系统提示。 */
    String systemPrompt();

    /**
     * 运行一个 turn：接收用户消息，驱动 ReAct 循环（推理→行动→观察→...），
     * 返回最终回复。
     *
     * @param sessionId 会话 ID
     * @param scopeKey   agent 作用域键
     * @param ctx        插件上下文
     * @param userMessage 用户输入
     * @return 最终助手回复
     */
    String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) throws Exception;

    /**
     * 流式运行一个 turn：逐 token 把助手回复增量下发给 {@code deltaSink}，返回完整回复。
     *
     * <p>默认回退实现：调用 {@link #run} 后整段下发（非真正流式）。
     * ReAct 实现覆盖为基于 {@code LlmModel.stream} 的逐 token 流式（纯对话，不调用工具——
     * 需要工具的回合用 {@link #run}）。
     *
     * @param deltaSink 每个增量 token 的回调
     * @return 完整助手回复
     */
    default String streamChat(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                              String userMessage, java.util.function.Consumer<String> deltaSink) throws Exception {
        String reply = run(sessionId, scopeKey, ctx, userMessage);
        if (reply != null && !reply.isEmpty()) deltaSink.accept(reply);
        return reply;
    }
}
