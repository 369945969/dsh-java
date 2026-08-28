package com.deepseek.dsh.llm.retry;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.exception.LlmException;
import com.deepseek.dsh.llm.adapter.LlmChunk;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.adapter.LlmRequest;
import com.deepseek.dsh.llm.adapter.LlmResponse;

/**
 * 重试装饰器 —— 对 {@link LlmModel} 增加指数退避重试。
 *
 * <p><b>重构后</b>：通过 {@link LlmException#isRecoverable()} 精确判定可重试性
 * （替代此前按消息字符串模糊匹配的脆弱逻辑）。4xx 不可重试，5xx/超时可重试。
 *
 * <p>设计模式：装饰器（在不修改被装饰者前提下增加重试行为）。
 */
public final class RetryLlmModel implements LlmModel {

    private static final Logger log = LoggerFactory.getLogger(RetryLlmModel.class);

    private final LlmModel delegate;
    private final int maxRetries;
    private final long baseDelayMillis;

    public RetryLlmModel(LlmModel delegate, int maxRetries, long baseDelayMillis) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
    }

    public RetryLlmModel(LlmModel delegate) {
        this(delegate, 3, 1000L);
    }

    @Override
    public LlmResponse chat(LlmRequest request) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return delegate.chat(request);
            } catch (LlmException e) {
                last = e;
                if (!e.isRecoverable() || attempt == maxRetries) throw e;
                long delay = baseDelayMillis * (1L << attempt);
                log.warn("LLM call failed (http={}), retry #{} ({}ms): {}",
                        e.httpStatus(), attempt + 1, delay, e.getMessage());
                Thread.sleep(delay);
            }
        }
        throw last;
    }

    @Override
    public Flow.Publisher<LlmChunk> stream(LlmRequest request) throws Exception {
        // 流式重试：仅重试连接建立阶段，开始接收数据后不再重试
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return delegate.stream(request);
            } catch (LlmException e) {
                last = e;
                if (!e.isRecoverable() || attempt == maxRetries) throw e;
                long delay = baseDelayMillis * (1L << attempt);
                log.warn("LLM stream connection failed (http={}), retry #{} ({}ms): {}",
                        e.httpStatus(), attempt + 1, delay, e.getMessage());
                Thread.sleep(delay);
            }
        }
        throw last;
    }

    @Override
    public LlmResponse streamCollect(LlmRequest request, Consumer<LlmChunk> onChunk) throws Exception {
        // 流式收集：直接委托被装饰者（重试由底层 streamCollect 的 SSE 连接隐式处理；
        // 已开始接收增量后不再重试，避免重复推送）
        return delegate.streamCollect(request, onChunk);
    }
}
