package com.deepseek.dsh.tools.pipeline;

import com.deepseek.dsh.core.middleware.Middleware;

/**
 * 工具执行中间件 —— 对应原 Harness 的 {@code tools/pre-execute} →
 * {@code tools/execute} → {@code tools/post-execute} 瀑布。
 *
 * <p>中间件可在工具实际执行前（前执行）、后（后执行）插入逻辑，
 * 例如权限校验、审批、日志、超时、副作用记录等。
 *
 * <p>设计模式：责任链（Chain of Responsibility）。
 */
@FunctionalInterface
public interface ToolMiddleware
        extends Middleware<ToolExecutionRequest, ToolExecutionResult> {

    /**
     * 若返回非 null，则短路管线直接返回该结果（拒绝执行）。
     * 返回 {@code null} 表示放行，继续调用 {@code next}。
     *
     * <p>实现示例（权限中间件）：
     * <pre>{@code
     * (req, next) -> {
     *     if (!isAllowed(req)) return ToolExecutionResult.error(req.toolCallId(), "无权限");
     *     ToolExecutionResult r = next.proceed(req);
     *     log(req, r);
     *     return r;
     * }
     * }</pre>
     */
    @Override
    ToolExecutionResult handle(ToolExecutionRequest request,
                               Next<ToolExecutionRequest, ToolExecutionResult> next);
}
