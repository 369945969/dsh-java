package com.deepseek.dsh.session.projection;

import java.util.Optional;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.session.log.SessionEvent.Projection;

/**
 * 会话投影缓存服务缝 —— 对应原 Harness 的 {@code ctx.sessionProjectionCache}。
 */
public interface SessionProjectionCacheService extends Service {

    /** 写入投影检查点。 */
    void put(SessionId sessionId, Projection projection);

    /** 读取缓存的投影（未命中返回 empty）。 */
    Optional<Projection> get(SessionId sessionId);

    /** 使某会话的投影缓存失效。 */
    void invalidate(SessionId sessionId);
}
