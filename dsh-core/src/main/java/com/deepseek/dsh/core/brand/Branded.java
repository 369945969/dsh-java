package com.deepseek.dsh.core.brand;

import java.util.Objects;

/**
 * 品牌化类型 ID 包装器 —— 不透明的跨边界标识符，
 * 避免与裸 {@link String} 或其他品牌化 ID 混淆。
 *
 * <p>对应原 TypeScript 版 Harness 的 {@code dsh-brand}。
 * 一个 {@code Branded<String>}（如 {@code SessionId}）在结构上区别于
 * 携带不同语义标签的另一个 {@code Branded<String>}，从而利用类型系统
 * 防止意外的跨类型赋值。
 *
 * <p>设计模式：值对象（DDD Value Object）。
 *
 * @param <T> 承载类型（通常是 {@link String}）
 * @param <Tag> 幻影类型标签，用于区分不同的品牌化 ID
 */
public abstract class Branded<T, Tag> {
    private final T value;

    protected Branded(T value) {
        this.value = Objects.requireNonNull(value, "品牌化值不能为 null");
    }

    /** 原始承载值。子类应优先提供领域专属访问器。 */
    @com.fasterxml.jackson.annotation.JsonValue
    public final T value() {
        return value;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Branded<?, ?> other = (Branded<?, ?>) o;
        return Objects.equals(value, other.value);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
