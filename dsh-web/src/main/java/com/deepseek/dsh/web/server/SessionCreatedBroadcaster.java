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
    private final java.util.concurrent.atomic.AtomicReference<Disposable> subscription =
            new java.util.concurrent.atomic.AtomicReference<>();

    public SessionCreatedBroadcaster(AgentContextHolder holder, ApiproxyDownlinkRegistry downlink) {
        this.holder = holder;
        this.downlink = downlink;
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
            var frame = hostFrame("host/session-added", java.util.Map.of(
                    "sessionId", event.sessionId().value(),
                    "blank", true));
            downlink.sendHostFrame(java.util.UUID.randomUUID().toString(), frame);
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
