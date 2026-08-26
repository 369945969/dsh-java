package com.deepseek.dsh.core.context;

/**
 * 插件（Cordis 的 "Service" 等价物）。挂载插件即调用 {@link #apply(Context)}，
 * 在此过程中插件从上下文读取其他服务，并贡献自己的服务 —— 通常通过
 * {@link Context#track(Disposable)} 或 {@link Context#register} 注册可逆副作用。
 *
 * <p>不存在特权核心：产品的每一部分（模型适配器、工具注册表、会话日志，
 * 甚至 agent loop 本身）都是挂载在其他插件旁边的插件，且每次注册都是可逆的。
 *
 * <p>设计模式：策略（可互换插件）+ 插件/SPI。
 */
public interface Plugin {

    /**
     * 将此插件挂载到上下文。在此注册服务与可逆副作用。对其他服务的依赖应
     * 延迟解析（通过 {@link Context#get(Class)}），这样加载顺序由服务需求决定，
     * 而非手动启动顺序。
     *
     * @param ctx 要挂载到的上下文
     * @return 用于撤销本插件所注册一切的 {@link Disposable}；
     *         若无需撤销则返回 {@code () -> {}}
     */
    Disposable apply(Context ctx);
}
