package com.deepseek.dsh.core.context;

/**
 * 能力缝插件基类 —— 消除每个插件重复的 {@code ctx.register(X.class, this)} 样板。
 *
 * <p>所有实现 {@link Plugin} + {@link Service} 的能力缝插件都遵循同一模式：
 * 在 {@link #apply(Context)} 中把自身注册到上下文。本基类将该样板抽出，
 * 子类只需声明服务类型，无需重复样板代码。
 *
 * <p>设计模式：模板方法 + 泛型参数化。
 *
 * @param <S> 服务接口类型（能力缝定义）
 */
public abstract class AbstractCapabilityPlugin<S extends Service> implements Plugin, Service {

    /** 子类返回服务接口的 Class 对象。 */
    protected abstract Class<S> serviceType();

    @Override
    public Disposable apply(Context ctx) {
        @SuppressWarnings("unchecked")
        S self = (S) this;
        return ctx.register(serviceType(), self);
    }
}
