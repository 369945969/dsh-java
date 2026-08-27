package com.deepseek.dsh.core.event;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Disposable;

/**
 * 类型化事件总线 —— 插件间通信的接缝。
 *
 * <p>对应 Cordis 的类型化事件，具备四种分发模式：
 * <ul>
 *   <li><b>emit</b> —— 即发即忘观察（监听器不得修改负载或阻塞链）。</li>
 *   <li><b>waterfall</b> —— around 中间件：每个监听器接收值并<em>必须</em>调用
 *       {@code next} 以继续，或通过提前返回来拒绝。{@code agent/pre-step}、
 *       {@code llm/stream} 等即以此实现。</li>
 *   <li><b>parallel</b> —— 并发扇出到所有监听器，收集首个非空返回。</li>
 *   <li><b>serial</b> —— 有序，返回值贯穿传递。</li>
 * </ul>
 *
 * <p>设计模式：观察者（发布/订阅）+ 责任链（waterfall）。
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final ConcurrentMap<Class<?>, List<Listener<?>>> listeners = new ConcurrentHashMap<>();

    /**
     * 订阅某事件类型。返回一个 {@link Disposable} 用于移除订阅（可逆副作用）。
     */
    @SuppressWarnings("unchecked")
    public <E> Disposable on(Class<E> eventType, Listener<E> listener) {
        List<Listener<?>> list = listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    /** 某类型的活跃监听器数量（主要用于测试/诊断）。 */
    public int listenerCount(Class<?> eventType) {
        return listeners.getOrDefault(eventType, List.of()).size();
    }

    // ---- emit（即发即忘观察） -----------------------------------------------

    @SuppressWarnings("unchecked")
    public <E> void emit(E event) {
        List<Listener<?>> list = listeners.get(event.getClass());
        if (list == null) return;
        for (Listener<?> l : list) {
            try {
                // emit 用直通 next，丢弃返回值
                ((Listener<E>) l).on(event, value -> value);
            } catch (RuntimeException e) {
                log.warn("emit listener threw exception {}: {}", event.getClass().getSimpleName(), e.toString());
            }
        }
    }

    // ---- waterfall（around 中间件） -----------------------------------------

    /**
     * waterfall 分发：监听器形成一条链；每个监听器接收值和一个
     * {@link Next}，它<em>必须</em>调用后者以继续。监听器可通过不调用
     * {@code next} 而直接返回值来短路（拒绝/提前退出）。
     *
     * @param eventType 事件类
     * @param event 初始负载
     * @param <E> 事件类型
     * @return 贯穿链后的最终值（若无监听器则为输入值）
     */
    @SuppressWarnings("unchecked")
    public <E> E waterfall(Class<E> eventType, E event) {
        List<Listener<?>> list = listeners.get(eventType);
        if (list == null || list.isEmpty()) return event;
        // 逆序构建链，使最先注册的监听器位于最外层
        Next<E> chain = value -> value;
        for (int i = list.size() - 1; i >= 0; i--) {
            final Listener<E> current = (Listener<E>) list.get(i);
            final Next<E> next = chain;
            chain = value -> current.on(value, next);
        }
        return chain.invoke(event);
    }

    // ---- serial（有序，贯穿返回值） ----------------------------------------

    @SuppressWarnings("unchecked")
    public <E, R> R serial(Class<E> eventType, E event, R initial, Function<E, R> extract) {
        List<Listener<?>> list = listeners.get(eventType);
        if (list == null) return initial;
        R acc = initial;
        for (Listener<?> l : list) {
            try {
                // serial 用直通 next，监听器可就地修改事件
                ((Listener<E>) l).on(event, value -> value);
            } catch (RuntimeException e) {
                log.warn("serial listener threw exception: {}", e.toString());
            }
            // serial 监听器就地修改事件；调用方重新提取
            acc = extract.apply(event);
        }
        return acc;
    }

    // ---- 监听器契约 --------------------------------------------------------

    /**
     * 监听器。唯一方法返回贯穿后的值：调用 {@code next.invoke(...)} 并返回其结果以继续链，
     * 或直接返回一个值而不调用 next 以短路。
     */
    @FunctionalInterface
    public interface Listener<E> {
        /**
         * 处理事件。
         * @param event 事件负载
         * @param next 链中的下一步；调用 {@link Next#invoke(Object)} 以继续
         * @return 贯穿后的值（短路时为监听器自行返回的值）
         */
        E on(E event, Next<E> next);
    }

    @FunctionalInterface
    public interface Next<E> {
        E invoke(E value);
    }
}
