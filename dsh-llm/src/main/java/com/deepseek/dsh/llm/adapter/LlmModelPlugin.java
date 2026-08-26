package com.deepseek.dsh.llm.adapter;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;

/**
 * LLM 模型插件 —— 将一个 {@link LlmModel} 实例注册到上下文，
 * 使其作为 {@code ctx.llm} 可被 agent loop 获取。
 *
 * <p>设计模式：适配器插件（把具体适配器适配为可注册的服务）。
 */
public record LlmModelPlugin(LlmModel model) implements Plugin, Service {

    @Override
    public Disposable apply(Context ctx) {
        return ctx.register(LlmModel.class, model);
    }
}
