package com.deepseek.dsh.subagent;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Service;

/**
 * subagent 能力缝 —— 对应原 Harness 的 {@code ctx.subagents}。
 *
 * <p>允许主 agent 将子任务委派给一个（可能配置不同的）子 agent 执行，
 * 父子通过谱系（lineage）关联，深度递增。能力缝使委派后端可插拔：
 * in-process fork、spawn-in-process、ACP、SDK 等。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code ForkInProcessProvider}（进程内 fork）。</li>
 *   <li><b>消费者</b>：{@code task} 委派工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + 代理（子 agent 代理父 agent 完成子任务）。
 */
public interface SubagentService extends Service {

    /**
     * 委派一个子任务给子 agent 执行。
     *
     * @param parentSessionId 父会话 ID
     * @param parentScopeKey  父作用域键
     * @param ctx             插件上下文
     * @param agent           要使用的子 agent（可自定义人格/工具）
     * @param task            子任务描述
     * @return 委派结果（摘要报告）
     */
    DelegationResult delegate(SessionId parentSessionId, ScopeKey parentScopeKey,
                             Context ctx, Agent agent, String task);
}
