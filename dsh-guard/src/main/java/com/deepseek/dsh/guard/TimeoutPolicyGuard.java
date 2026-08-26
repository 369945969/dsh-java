package com.deepseek.dsh.guard;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.deepseek.dsh.core.exception.ToolException;
import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.pipeline.ToolMiddleware;

/**
 * 工具调用超时策略 —— 对应原 Harness 的 {@code timeout-policy}。
 *
 * <p>为每次工具调用设置独立超时：在独立线程中执行工具，
 * 超时则抛出可恢复的 {@link ToolException}。
 *
 * <p>设计模式：责任链中间件（装饰工具执行管线）+ 代理（超时代理包裹执行）。
 */
public final class TimeoutPolicyGuard implements ToolMiddleware {

    /** 默认每工具超时秒数。 */
    private final int defaultTimeoutSeconds;

    public TimeoutPolicyGuard() {
        this(120);
    }

    public TimeoutPolicyGuard(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    @Override
    public ToolExecutionResult handle(ToolExecutionRequest request,
                                      Next<ToolExecutionRequest, ToolExecutionResult> next) {
        // 从参数中提取 timeout（若工具定义了该字段）
        int timeout = defaultTimeoutSeconds;
        Object userTimeout = request.arguments().get("timeout");
        if (userTimeout instanceof Number n) {
            timeout = n.intValue();
        }

        if (timeout <= 0) {
            return next.proceed(request); // 不超时
        }

        CompletableFuture<ToolExecutionResult> future = CompletableFuture.supplyAsync(
                () -> next.proceed(request));
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolException(request.toolName(), request.toolCallId(),
                    "工具执行超时（" + timeout + "s）", null, true); // 可恢复
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new ToolException(request.toolName(), request.toolCallId(),
                    "工具执行异常: " + cause.getMessage(), cause, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException(request.toolName(), request.toolCallId(),
                    "工具执行被中断", e, true);
        }
    }
}
