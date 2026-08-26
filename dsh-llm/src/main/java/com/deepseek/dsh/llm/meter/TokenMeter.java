package com.deepseek.dsh.llm.meter;

import java.util.concurrent.atomic.AtomicLong;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.llm.adapter.LlmResponse;

/**
 * Token 用量计量器 —— 累计 prompt/completion/total token 用量。
 *
 * <p><b>重构后</b>：继承 {@link AbstractCapabilityPlugin}，消除 {@code ctx.register} 样板，
 * 只需声明服务类型。
 *
 * <p>设计模式：观察者（聚合统计）+ 模板方法（插件基类）。
 */
public final class TokenMeter extends AbstractCapabilityPlugin<TokenMeterService>
        implements TokenMeterService {

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();

    @Override
    protected Class<TokenMeterService> serviceType() {
        return TokenMeterService.class;
    }

    @Override
    public void record(LlmResponse response) {
        if (response.usage() == null) return;
        promptTokens.addAndGet(response.usage().promptTokens());
        completionTokens.addAndGet(response.usage().completionTokens());
        totalTokens.addAndGet(response.usage().totalTokens());
    }

    @Override
    public long totalPromptTokens() {
        return promptTokens.get();
    }

    @Override
    public long totalCompletionTokens() {
        return completionTokens.get();
    }

    @Override
    public long totalTokens() {
        return totalTokens.get();
    }
}
