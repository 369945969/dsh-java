package com.deepseek.dsh.telemetry;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 空实现遥测提供者 —— 对应原 Harness 的 no-op 默认。
 *
 * <p>所有观测点为无操作，零开销，供未启用遥测的部署使用。
 *
 * <p>设计模式：策略的具体实现 + 空对象（Null Object）。
 */
public final class NoopTelemetryProvider
        extends AbstractCapabilityPlugin<TelemetryService>
        implements TelemetryService {

    @Override
    protected Class<TelemetryService> serviceType() {
        return TelemetryService.class;
    }

    @Override
    public Span startSpan(String name, Span.Kind kind) {
        return NoopSpan.INSTANCE;
    }

    @Override
    public void recordMetric(String name, double value, String... attrs) {
        // no-op
    }

    @Override
    public void recordCounter(String name, long delta, String... attrs) {
        // no-op
    }

    /** 单例空 span。 */
    private static final class NoopSpan implements Span {
        static final NoopSpan INSTANCE = new NoopSpan();

        @Override public String name() { return ""; }
        @Override public Kind kind() { return Kind.INTERNAL; }
        @Override public Span setAttribute(String key, String value) { return this; }
        @Override public Span setAttribute(String key, long value) { return this; }
        @Override public Span setAttribute(String key, boolean value) { return this; }
        @Override public Span recordException(Throwable throwable) { return this; }
        @Override public void end() { /* no-op */ }
    }
}
