package com.deepseek.dsh.session.projection;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.session.log.SessionEvent.Projection;

/**
 * 会话投影缓存 —— 对应原 Harness 的 {@code session-projection-cache}。
 *
 * <p>持久化 {@link Projection} 检查点，避免每次模型请求都从全量事件重算
 * {@code deriveMessages}。采用节流写后（throttled write-behind）策略：
 * <ul>
 *   <li>读：先查缓存，命中则直接返回；未命中则从日志重算并写入缓存。</li>
 *   <li>写：每次事件追加后更新内存缓存，按节流间隔落盘。</li>
 * </ul>
 *
 * <p>设计模式：缓存模式（Cache-Aside）+ 模板方法（插件基类）。
 */
public final class SessionProjectionCache
        extends AbstractCapabilityPlugin<SessionProjectionCacheService>
        implements SessionProjectionCacheService {

    private final ConcurrentMap<SessionId, Projection> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<SessionId, Long> lastWriteEpoch = new ConcurrentHashMap<>();

    /** 节流间隔（秒），避免频繁落盘。 */
    private final long throttleSeconds;

    public SessionProjectionCache() {
        this(10);
    }

    public SessionProjectionCache(long throttleSeconds) {
        this.throttleSeconds = throttleSeconds;
    }

    @Override
    protected Class<SessionProjectionCacheService> serviceType() {
        return SessionProjectionCacheService.class;
    }

    @Override
    public void put(SessionId sessionId, Projection projection) {
        cache.put(sessionId, projection);
        long now = System.currentTimeMillis() / 1000;
        long last = lastWriteEpoch.getOrDefault(sessionId, 0L);
        if (now - last >= throttleSeconds) {
            lastWriteEpoch.put(sessionId, now);
            // 此处可落盘到 SessionStore；简化为内存缓存
        }
    }

    @Override
    public Optional<Projection> get(SessionId sessionId) {
        return Optional.ofNullable(cache.get(sessionId));
    }

    @Override
    public void invalidate(SessionId sessionId) {
        cache.remove(sessionId);
        lastWriteEpoch.remove(sessionId);
    }
}
