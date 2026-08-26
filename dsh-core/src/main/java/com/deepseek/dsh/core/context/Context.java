package com.deepseek.dsh.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.event.EventBus;

/**
 * {@code Context} 是一个服务仓库 —— 插件基座的核心（Cordis context 的 Java 等价物）。
 *
 * <p>从原 Harness 移植的关键思想：
 * <ul>
 *   <li><b>插件实现 {@link Service}。</b> 插件相互并排挂载，没有需要打补丁的特权核心。</li>
 *   <li><b>服务依赖通过 {@link Plugin#apply(Context)} 声明。</b> 插件从上下文读取其他服务，
 *       并贡献自己的服务。</li>
 *   <li><b>注册即可逆副作用。</b> 每次 {@code ctx.register(...)} 都返回一个
 *       {@link Disposable}，其 {@link Disposable#dispose()} 撤销该注册 ——
 *       对作用域/agent 本地上下文与运行时插件卸载至关重要。</li>
 * </ul>
 *
 * <p><b>作用域。</b> Harness 有两级：全局注册（每个 agent 可见）与
 * <em>作用域</em> 注册（仅归一个 {@link ScopeKey} 所有）。作用域注册会
 * <em>遮蔽</em> 全局注册（最具体者胜）。{@link #scoped(ScopeKey)} 返回一个子上下文，
 * 其作用域写不会泄漏到父级，但当本作用域没有自己的条目时，读取会穿透到父级。
 *
 * <p>设计模式：注册表、组合（父子作用域）、Disposable（资源生命周期）、
 * 装饰器（作用域视图包装全局上下文）。
 */
public class Context {

    private static final Logger log = LoggerFactory.getLogger(Context.class);

    private final ScopeKey scopeKey;
    private final Context parent;
    private final ConcurrentMap<Key<?>, Object> services = new ConcurrentHashMap<>();
    private final List<Disposable> effects = new ArrayList<>();
    private final EventBus eventBus;

    private Context(ScopeKey scopeKey, Context parent, EventBus eventBus) {
        this.scopeKey = scopeKey;
        this.parent = parent;
        this.eventBus = eventBus;
    }

    /** 创建一个全新的全局根上下文。 */
    public static Context root() {
        return new Context(null, null, new EventBus());
    }

    /** 创建一个共享事件总线的全新根上下文（用于测试）。 */
    public static Context root(EventBus eventBus) {
        return new Context(null, null, eventBus);
    }

    /**
     * 派生一个作用域子上下文。此处的写是隔离的；
     * 当本作用域没有自己的条目时，读取会穿透到本上下文。
     */
    public Context scoped(ScopeKey key) {
        return new Context(key, this, this.eventBus);
    }

    /** 本上下文所属的作用域键；全局根上下文为 {@code null}。 */
    public ScopeKey scopeKey() {
        return scopeKey;
    }

    /** 共享的类型化事件总线（父子作用域间为同一实例）。 */
    public EventBus events() {
        return eventBus;
    }

    // ---- 服务仓库 ----------------------------------------------------------

    /**
     * 在类型化键下注册服务实例。返回一个 {@link Disposable}，
     * 在 dispose 时移除该注册。
     */
    public <S> Disposable register(Class<S> type, S service) {
        return register(type.getName(), service);
    }

    /**
     * 在字符串键下注册服务实例（允许同一类型的多个提供者，如能力提供者）。
     */
    public <S> Disposable register(String key, S service) {
        Key<S> k = Key.of(key);
        Object prev = services.putIfAbsent(k, service);
        if (prev != null) {
            throw new IllegalStateException(
                "service already registered for key '" + key + "': " + prev);
        }
        Disposable d = () -> {
            if (services.remove(k, service)) {
                log.debug("unregistered service '{}'", key);
            }
        };
        track(d);
        return d;
    }

    /**
     * 按类型查找服务，当本作用域上下文没有自己的条目时穿透到父作用域（遮蔽）。
     */
    @SuppressWarnings("unchecked")
    public <S> Optional<S> get(Class<S> type) {
        return get(type.getName()).map(v -> (S) v);
    }

    /** 按字符串键查找服务，带父级穿透。 */
    public Optional<Object> get(String key) {
        Key<Object> k = Key.of(key);
        Object v = services.get(k);
        if (v != null) return Optional.of(v);
        return parent != null ? parent.get(key) : Optional.empty();
    }

    /** 便捷方法：要求服务，否则抛异常。 */
    public <S> S require(Class<S> type) {
        return get(type).orElseThrow(() -> new IllegalStateException(
            "未注册服务: " + type.getName()));
    }

    // ---- 可逆副作用 --------------------------------------------------------

    /** 跟踪一个 disposable，使它在本上下文 dispose 时被撤销。 */
    public Disposable track(Disposable disposable) {
        effects.add(disposable);
        return () -> {
            effects.remove(disposable);
            disposable.dispose();
        };
    }

    /** 释放所有被跟踪的副作用并清空服务（作用域拆解）。 */
    public void dispose() {
        // 按注册的逆序 dispose
        for (int i = effects.size() - 1; i >= 0; i--) {
            try {
                effects.get(i).dispose();
            } catch (RuntimeException e) {
                log.warn("error disposing effect", e);
            }
        }
        effects.clear();
        services.clear();
    }

    // ---- 键 ---------------------------------------------------------------

    private record Key<T>(String name) {
        private static final ConcurrentMap<String, Key<?>> cache = new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked")
        private static <T> Key<T> of(String name) {
            return (Key<T>) cache.computeIfAbsent(name, Key::new);
        }
    }
}
