package com.deepseek.dsh.telemetry;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.registry.ToolContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 遥测能力缝测试 —— no-op / 日志后端 + 中间件包装。
 */
class TelemetryTest {

    @Test
    void noop后端零开销不抛() {
        var t = new NoopTelemetryProvider();
        Span s = t.startSpan("x", Span.Kind.CLIENT);
        s.setAttribute("k", "v").setAttribute("n", 1L).setAttribute("b", true);
        s.recordException(new RuntimeException("boom"));
        s.end();
        // 二次 end 幂等
        s.end();
        t.recordMetric("m", 1.0, "k", "v");
        t.recordCounter("c", 1);
        assertEquals("", s.name());
        assertEquals(Span.Kind.INTERNAL, s.kind());
    }

    @Test
    void 日志后端记录span属性与异常() {
        var t = new LoggingTelemetryProvider();
        Span s = t.startSpan("op", Span.Kind.SERVER)
                .setAttribute("tool", "bash")
                .setAttribute("ok", false);
        s.recordException(new IllegalStateException("err"));
        s.end();
        // 不抛即通过；结构化日志在 SLF4J 输出（NOP logger 下静默）
        assertEquals("op", s.name());
        assertEquals(Span.Kind.SERVER, s.kind());
    }

    @Test
    void 中间件包装工具执行不破坏结果() {
        var t = new NoopTelemetryProvider();
        var mw = new TelemetryMiddleware(t);
        ToolContext ctx = new ToolContext(
                com.deepseek.dsh.core.brand.SessionId.of("s"), null, null);
        ToolExecutionRequest req = new ToolExecutionRequest(
                "bash", "c1", Map.of(), ctx);
        var result = mw.handle(req, r -> ToolExecutionResult.ok(r.toolCallId(), "done"));
        assertEquals("done", result.text());
        assertFalse(result.isError());
    }

    @Test
    void 中间件记录错误结果与异常() {
        var t = new LoggingTelemetryProvider();
        var mw = new TelemetryMiddleware(t);
        ToolContext ctx = new ToolContext(
                com.deepseek.dsh.core.brand.SessionId.of("s"), null, null);
        ToolExecutionRequest req = new ToolExecutionRequest(
                "bash", "c2", Map.of(), ctx);
        // next 抛异常 → 中间件记录后原样抛
        assertThrows(RuntimeException.class,
                () -> mw.handle(req, r -> { throw new RuntimeException("boom"); }));
    }
}
