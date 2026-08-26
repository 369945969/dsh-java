package com.deepseek.dsh.core.middleware;

/**
 * 中间件 —— 可组合的横切钩子，贯穿 agent loop 的各个阶段
 * （借鉴 AgentScope 的 Middleware 概念）。
 *
 * <p>中间件按顺序组成一条链：每个中间件收到请求后，可选择在调用
 * {@link Next#proceed(Object)} 前后做前置/后置处理，也可短路直接返回。
 *
 * <p>典型中间件：
 * <ul>
 *   <li>权限检查中间件（Permission）—— 在工具执行前校验权限。</li>
 *   <li>上下文压缩中间件（Context）—— 在模型调用前压缩历史。</li>
 *   <li>系统提示注入中间件（SystemPrompt）—— 注入系统提示。</li>
 *   <li>模型调用中间件（Model）—— 实际调用 LLM。</li>
 *   <li>回复中间件（Reply）—— 拦截最终回复。</li>
 * </ul>
 *
 * <p>设计模式：责任链（Chain of Responsibility）+ 装饰器。
 *
 * @param <T> 请求载荷类型
 * @param <R> 返回结果类型
 */
@FunctionalInterface
public interface Middleware<T, R> {

    /**
     * 处理请求。
     *
     * @param request 请求载荷
     * @param next 链中的下一个中间件；调用 {@link Next#proceed(Object)} 以继续
     * @return 处理结果
     */
    R handle(T request, Next<T, R> next);

    /** 链中下一步的调用句柄。 */
    @FunctionalInterface
    interface Next<T, R> {
        R proceed(T request);
    }
}
