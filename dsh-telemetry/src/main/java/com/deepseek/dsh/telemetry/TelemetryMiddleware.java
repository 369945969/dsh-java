package com.deepseek.dsh.telemetry;

import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.pipeline.ToolMiddleware;

/**
 * 遥测中间件 —— 对应原 Harness 把工具执行包装进 span 的观测点。
 *
 * <p>在工具执行前后创建 span：属性记录工具名与调用 ID，结束记录是否出错与
 * 异常。后端 no-op 时零开销（{@link NoopTelemetryProvider}）。
 *
 * <p>设计模式：责任链中间件（装饰 next 后再观测）+ 观察者。
 */
public final class TelemetryMiddleware implements ToolMiddleware {

    private final TelemetryService telemetry;

    public TelemetryMiddleware(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public ToolExecutionResult handle(ToolExecutionRequest request,
                                      Next<ToolExecutionRequest, ToolExecutionResult> next) {
        Span span = telemetry.startSpan("tool/" + request.toolName(), Span.Kind.INTERNAL)
                .setAttribute("tool", request.toolName())
                .setAttribute("call.id", request.toolCallId());
        long start = System.nanoTime();
        try {
            ToolExecutionResult result = next.proceed(request);
            span.setAttribute("result.is_error", result.isError());
            if (result.isError()) {
                span.recordException(new RuntimeException(result.text()));
            }
            telemetry.recordCounter("tool.invocations", 1,
                    "tool", request.toolName(), "ok", String.valueOf(!result.isError()));
            telemetry.recordMetric("tool.duration_ms",
                    (System.nanoTime() - start) / 1_000_000.0,
                    "tool", request.toolName());
            return result;
        } catch (RuntimeException e) {
            span.recordException(e);
            telemetry.recordCounter("tool.invocations", 1,
                    "tool", request.toolName(), "ok", "false");
            throw e;
        } finally {
            span.end();
        }
    }
}
