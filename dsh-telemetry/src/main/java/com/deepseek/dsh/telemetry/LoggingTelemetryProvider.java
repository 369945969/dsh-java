package com.deepseek.dsh.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 日志遥测提供者 —— OpenTelemetry 风格的结构化日志后端。
 *
 * <p>把 span/metric/counter 以 OTel 语义输出到 SLF4J：span 结束时记录
 * {@code name / kind / duration_ms / attributes / exception}；metric/counter
 * 带属性输出。无需引入 OTel SDK 依赖即可工作；后续要接入真实导出器
 * （OTLP/Jaeger），实现本接口即可（策略切换点）。
 *
 * <p>设计模式：策略的具体实现 + 适配器（适配 OTel 语义到日志）。
 */
public final class LoggingTelemetryProvider
        extends AbstractCapabilityPlugin<TelemetryService>
        implements TelemetryService {

    private static final Logger log = LoggerFactory.getLogger("dsh.telemetry");

    @Override
    protected Class<TelemetryService> serviceType() {
        return TelemetryService.class;
    }

    @Override
    public Span startSpan(String name, Span.Kind kind) {
        return new LoggingSpan(name, kind);
    }

    @Override
    public void recordMetric(String name, double value, String... attrs) {
        log.info("metric {}={} {}", name, value, attrsToString(attrs));
    }

    @Override
    public void recordCounter(String name, long delta, String... attrs) {
        log.info("counter {} +={} {}", name, delta, attrsToString(attrs));
    }

    /** 把交错键值对渲染为字符串。 */
    private static String attrsToString(String... attrs) {
        if (attrs == null || attrs.length == 0) return "{}";
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            m.put(attrs[i], attrs[i + 1]);
        }
        return m.toString();
    }

    /** 日志 span：结束时一次性输出，避免中途刷新抖动。 */
    private static final class LoggingSpan implements Span {
        private final String name;
        private final Kind kind;
        private final long startNanos = System.nanoTime();
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String exception = null;
        private boolean ended = false;

        LoggingSpan(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override public String name() { return name; }
        @Override public Kind kind() { return kind; }

        @Override
        public Span setAttribute(String key, String value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public Span setAttribute(String key, long value) {
            attributes.put(key, String.valueOf(value));
            return this;
        }

        @Override
        public Span setAttribute(String key, boolean value) {
            attributes.put(key, String.valueOf(value));
            return this;
        }

        @Override
        public Span recordException(Throwable throwable) {
            this.exception = throwable == null ? null : throwable.toString();
            return this;
        }

        @Override
        public void end() {
            if (ended) return;
            ended = true;
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (exception != null) {
                log.warn("span {} kind={} dur={}ms attrs={} exc={}",
                        name, kind, durationMs, attributes, exception);
            } else {
                log.info("span {} kind={} dur={}ms attrs={}",
                        name, kind, durationMs, attributes);
            }
        }
    }
}
