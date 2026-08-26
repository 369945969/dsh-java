package com.deepseek.dsh.core.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 中间件调度器 —— 把一组有序的 {@link Middleware} 编译为单条可调用链。
 *
 * <p>构建时逆序拼接：最先添加的中间件位于链的最外层，最后添加的位于最内层
 * （通常是最内层的终端处理器）。调度器的 {@link #dispatch} 方法以一条
 * 责任链的方式依次执行，中间件可在 {@code next} 调用前后插入逻辑。
 *
 * <p>设计模式：责任链构建器。
 *
 * @param <T> 请求载荷类型
 * @param <R> 返回结果类型
 */
public final class MiddlewareChain<T, R> {

    private final List<Middleware<T, R>> middlewares;
    private final Middleware.Next<T, R> terminator;

    private MiddlewareChain(List<Middleware<T, R>> middlewares, Middleware.Next<T, R> terminator) {
        this.middlewares = List.copyOf(middlewares);
        this.terminator = Objects.requireNonNull(terminator);
    }

    /** 创建一个链构建器。 */
    public static <T, R> Builder<T, R> builder(Middleware.Next<T, R> terminator) {
        return new Builder<>(terminator);
    }

    /** 依次执行中间件链并返回最终结果。 */
    public R dispatch(T request) {
        // 逆序构造：最内层是 terminator，逐层包裹中间件
        Middleware.Next<T, R> chain = terminator;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            final Middleware<T, R> mw = middlewares.get(i);
            final Middleware.Next<T, R> next = chain;
            chain = req -> mw.handle(req, next);
        }
        return chain.proceed(request);
    }

    /** 链构建器。 */
    public static final class Builder<T, R> {
        private final List<Middleware<T, R>> list = new ArrayList<>();
        private final Middleware.Next<T, R> terminator;

        private Builder(Middleware.Next<T, R> terminator) {
            this.terminator = terminator;
        }

        /** 在链首部添加一个中间件（将位于最外层）。 */
        public Builder<T, R> add(Middleware<T, R> middleware) {
            list.add(middleware);
            return this;
        }

        /** 构建不可变链。 */
        public MiddlewareChain<T, R> build() {
            return new MiddlewareChain<>(list, terminator);
        }
    }
}
