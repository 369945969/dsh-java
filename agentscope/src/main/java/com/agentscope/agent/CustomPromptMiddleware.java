package com.agentscope.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 自定义提示词中间件 —— 按请求参数注入系统提示词与 skill 提示词。
 *
 * <p>从 RuntimeContext 读取两个字符串键：
 * <ul>
 *   <li><b>customSysPrompt</b> —— 非空时覆盖默认系统提示词</li>
 *   <li><b>skillPrompt</b> —— 非空时追加到系统提示词末尾作为技能指令</li>
 * </ul>
 * 两个参数均由 Web/WS 层从请求体传入 RuntimeContext，中间件在 agentscope
 * 的 onSystemPrompt 阶段读取并修改系统提示词。
 *
 * <p>设计模式：中间件（AOP 拦截，解耦请求参数与 agent 装配）。
 */
public class CustomPromptMiddleware implements MiddlewareBase {

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        String result = systemPrompt;

        // 1) 按请求覆盖系统提示词（customSysPrompt 非空 → 替换）
        String customSys = ctx.get("customSysPrompt");
        if (customSys != null && !customSys.isBlank()) {
            result = customSys;
        }

        // 2) 追加 skill 提示词（skillPrompt 非空 → 追加技能指令）
        String skillPrompt = ctx.get("skillPrompt");
        if (skillPrompt != null && !skillPrompt.isBlank()) {
            result = result + "\n\n## Skill Instruction\n" + skillPrompt;
        }

        return Mono.just(result);
    }
}
