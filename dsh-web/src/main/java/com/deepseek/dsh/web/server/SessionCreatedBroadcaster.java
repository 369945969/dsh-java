package com.deepseek.dsh.web.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.session.SessionCreatedEvent;

/**
 * 会话创建广播 —— 订阅 {@link SessionCreatedEvent}，向已连接的 host WebSocket 下行流
 * 推送 {@code host/session-added} 帧，使前端会话列表无需刷新即可实时新增。
 *
 * <p>同一进程内任何入口新建会话（web {@code session.create}、fork、子 agent 委派等）都会触发
 * {@link com.deepseek.dsh.session.SessionManager} 发出该事件；本组件统一广播，避免各入口各自发帧重复。
 *
 * <p>订阅延迟到上下文装配完成（{@code AgentContextHolder} 提供根 {@code Context}）后在 host 下行流建立时建立。
 *
 * <p>设计模式：观察者（订阅事件源）+ 单向数据流（事件 → 下行帧）。
 */
@Component
public class SessionCreatedBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SessionCreatedBroadcaster.class);

    private final AgentContextHolder holder;
    private final ApiproxyDownlinkRegistry downlink;
    private final RemoteMuxRegistry remoteMux;
    private final java.util.concurrent.atomic.AtomicReference<Disposable> subscription =
            new java.util.concurrent.atomic.AtomicReference<>();

    public SessionCreatedBroadcaster(AgentContextHolder holder, ApiproxyDownlinkRegistry downlink,
                                     RemoteMuxRegistry remoteMux) {
        this.holder = holder;
        this.downlink = downlink;
        this.remoteMux = remoteMux;
    }

    /** Subscribe on application ready (context is assembled by then), not waiting for a host WebSocket. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ensureSubscribed();
    }

    /** 惰性建立订阅：上下文在 ApplicationReadyEvent 后才装配完成。host 下行流建立时调用。 */
    public void ensureSubscribed() {
        if (subscription.get() != null) {
            return;
        }
        synchronized (this) {
            if (subscription.get() != null) {
                return;
            }
            Disposable d = holder.context().events().on(SessionCreatedEvent.class, (event, next) -> {
                broadcast(event);
                return next.invoke(event);
            });
            subscription.set(d);
        }
    }

    private void broadcast(SessionCreatedEvent event) {
        try {
            String sid = event.sessionId().value();
            long now = System.currentTimeMillis();
            // 1) legacy host/session-added frame (events.host downlink)
            var frame = hostFrame("host/session-added", java.util.Map.of(
                    "sessionId", sid,
                    "blank", true));
            downlink.sendHostFrame(java.util.UUID.randomUUID().toString(), frame);
            // 2) new-protocol api-session/added emit ($events stream on remote.mux)
            var summary = java.util.Map.of(
                    "sessionId", sid,
                    "updatedAt", now,
                    "running", false,
                    "blank", true,
                    "cwd", System.getProperty("user.dir"),
                    "projections", java.util.Map.of("asOfSeq", 0, "values",
                            java.util.Map.of("title", "新会话", "blank", true)));
            remoteMux.broadcastEmit("api-session/added", new Object[]{summary});
        } catch (Exception e) {
            log.warn("broadcast session-added failed for {}: {}", event.sessionId(), e.toString());
        }
    }

    private static java.util.Map<String, Object> hostFrame(String type, java.util.Map<String, Object> fields) {
        java.util.Map<String, Object> f = new java.util.LinkedHashMap<>(fields);
        f.put("type", type);
        return f;
    }
}
