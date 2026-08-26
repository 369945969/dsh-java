package com.deepseek.dsh.web.server;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.context.Context;

/**
 * Agent 上下文持有者 —— 由 Spring 管理，桥接插件上下文与 Agent。
 *
 * <p>供 {@code AgentController} 获取当前运行的 {@link Context} 与 {@link Agent}。
 */
public interface AgentContextHolder {

    /** 当前插件上下文。 */
    Context context();

    /** 当前 agent。 */
    Agent agent();
}
