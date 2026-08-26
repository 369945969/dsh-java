package com.deepseek.dsh.core.context;

/**
 * 一次性资源句柄，其 {@link #dispose()} 会撤销一次注册。
 *
 * <p>实现需保证幂等 —— 重复调用 dispose 是无操作。
 * 设计模式：Disposable / 类 RAII 资源生命周期。
 */
@FunctionalInterface
public interface Disposable {
    void dispose();
}
