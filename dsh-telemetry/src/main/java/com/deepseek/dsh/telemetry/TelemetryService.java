package com.deepseek.dsh.telemetry;

import com.deepseek.dsh.core.context.Service;

/**
 * 遥测能力缝 —— 对应原 Harness 的 {@code ctx.telemetry} / OpenTelemetry。
 *
 * <p>定义「观测什么」（Span、Metric、Counter），不规定「如何导出」：具体后端
 * （no-op、日志、真实 OTel SDK {@code io.opentelemetry:opentelemetry-api}）
 * 实现本接口并注册为 {@code telemetry} 服务。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code NoopTelemetryProvider}（默认 no-op）、
 *       {@code LoggingTelemetryProvider}（结构化日志，OTel 风格）。</li>
 *   <li><b>消费者</b>：{@code TelemetryMiddleware}（包装工具执行的 span）、
 *       agent loop（包装 turn/step 的 span）。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI（服务提供者接口）+ 观察者（观测点）。
 */
public interface TelemetryService extends Service {

    /** 启动一个跨度。返回的句柄在 {@link Span#end()} 后失效。 */
    Span startSpan(String name, Span.Kind kind);

    /** 启动一个内部跨度（便捷重载）。 */
    default Span startSpan(String name) {
        return startSpan(name, Span.Kind.INTERNAL);
    }

    /**
     * 记录一个度量值（可加性仪表，对齐 OTel {@code DoubleHistogram}）。
     *
     * @param name  度量名（点分，如 {@code tool.duration}）
     * @param value 度量值
     * @param attrs 附加属性键值对（交错：k1,v1,k2,v2...）；可空
     */
    void recordMetric(String name, double value, String... attrs);

    /**
     * 计数器自增（对齐 OTel {@code LongCounter}）。
     *
     * @param name  计数器名
     * @param delta 自增量（通常 1）
     * @param attrs 附加属性键值对；可空
     */
    void recordCounter(String name, long delta, String... attrs);
}
