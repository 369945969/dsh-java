package com.deepseek.dsh.telemetry;

/**
 * 遥测 Span 句柄 —— 对应 OpenTelemetry 的 {@code Span}。
 *
 * <p>一个跨度记录一次操作的开始/结束、属性与异常。{@link #end()} 后不可再用。
 * 句柄由 {@link TelemetryService#startSpan} 创建；属性与异常可链式附加。
 *
 * <p>设计模式：值对象 + 资源生命周期（RAII 式 end）。
 */
public interface Span {

    /** 跨度种类（对齐 OTel：INTERNAL/CLIENT/SERVER/PRODUCER/CONSUMER）。 */
    enum Kind { INTERNAL, CLIENT, SERVER, PRODUCER, CONSUMER }

    /** 跨度名。 */
    String name();

    /** 跨度种类。 */
    Kind kind();

    /** 附加一个字符串属性。 */
    Span setAttribute(String key, String value);

    /** 附加一个长整型属性。 */
    Span setAttribute(String key, long value);

    /** 附加一个布尔属性。 */
    Span setAttribute(String key, boolean value);

    /** 记录一次异常事件（不抛出，仅观测）。 */
    Span recordException(Throwable throwable);

    /** 结束跨度。幂等。 */
    void end();
}
