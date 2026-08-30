package com.deepseek.dsh.session;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.session.persistence.SessionStore;

/**
 * 会话管理器 —— 持有活跃 {@link SessionLog} 的注册表，并提供持久化协调。
 *
 * <p>作为插件挂载时，在 {@code ctx} 上注册自身为 {@link Sessions} 服务
 * （{@code ctx.sessions} 等价物），供 agent loop 查询/创建会话。
 *
 * <p>设计模式：注册表 + 门面（协调内存日志与持久化后端）。
 */
public final class SessionManager implements Plugin, Sessions, Service {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final SessionStore store;
    private final ConcurrentMap<SessionId, SessionLog> active = new ConcurrentHashMap<>();

    public SessionManager(SessionStore store) {
        this.store = store;
    }

    @Override
    public Disposable apply(Context ctx) {
        Disposable reg = ctx.register(Sessions.class, this);
        return reg;
    }

    @Override
    public SessionLog create() {
        SessionId id = SessionId.of(java.util.UUID.randomUUID().toString());
        SessionLog log = new SessionLog(id);
        active.put(id, log);
        return log;
    }

    @Override
    public Optional<SessionLog> get(SessionId id) {
        return Optional.ofNullable(active.get(id));
    }

    @Override
    public SessionLog getOrCreate(SessionId id) {
        return active.computeIfAbsent(id, k -> {
            SessionLog fresh = new SessionLog(id);
            // 从持久化后端重放历史
            try {
                for (SessionEvent e : store.load(id)) {
                    fresh.append(e.type(), e.data(), e.surfaceOp());
                }
            } catch (IOException ex) {
                log.warn("Failed to replay session {} history: {}", id, ex.toString());
            }
            return fresh;
        });
    }

    @Override
    public void persist(SessionEvent event) {
        try {
            store.append(event);
        } catch (IOException e) {
            log.warn("Failed to persist event seq={}: {}", event.seq(), e.toString());
        }
    }

    @Override
    public List<SessionId> list() {
        java.util.Set<SessionId> all = new java.util.LinkedHashSet<>(active.keySet());
        try {
            all.addAll(store.listAll());
        } catch (IOException e) {
            log.warn("Failed to list persisted sessions: {}", e.toString());
        }
        return List.copyOf(all);
    }
}
