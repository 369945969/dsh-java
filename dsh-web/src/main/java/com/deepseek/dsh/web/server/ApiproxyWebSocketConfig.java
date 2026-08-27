package com.deepseek.dsh.web.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * apiproxy 两条下行 WebSocket 流注册 —— 对应原 Harness 的 mux/host downlinks。
 *
 * <p>mux 连接建立时，异步推送所有已有会话的 title 投影（session/projection），
 * 使侧边栏在页面刷新后立即显示问答标题（而非 cwd basename），无需逐个点击。
 */
@Configuration
public class ApiproxyWebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ApiproxyWebSocketConfig.class);

    private final ApiproxyDownlinkRegistry registry;
    private final AgentContextHolder holder;

    public ApiproxyWebSocketConfig(ApiproxyDownlinkRegistry registry, AgentContextHolder holder) {
        this.registry = registry;
        this.holder = holder;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new DownlinkHandler(this.registry, this.holder), "/api/events.mux").setAllowedOrigins("*");
        registry.addHandler(new DownlinkHandler(this.registry, this.holder), "/api/events.host").setAllowedOrigins("*");
    }

    static final class DownlinkHandler extends TextWebSocketHandler {
        private final ApiproxyDownlinkRegistry registry;
        private final AgentContextHolder holder;

        DownlinkHandler(ApiproxyDownlinkRegistry registry, AgentContextHolder holder) {
            this.registry = registry;
            this.holder = holder;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            if (session.getUri() != null && session.getUri().toString().contains("events.mux")) {
                registry.registerMux(session);
                Thread.startVirtualThread(() -> pushAllSessionTitles());
            } else {
                registry.registerHost(session);
            }
        }

        /** Push session/projection (title) for every session so the sidebar shows titles on initial load. */
        private void pushAllSessionTitles() {
            try {
                var ctx = holder.context();
                var sessions = ctx.require(com.deepseek.dsh.session.Sessions.class);
                for (var sid : sessions.list()) {
                    var slog = sessions.get(sid).orElse(null);
                    if (slog == null || slog.size() == 0) continue;
                    String title = "新会话";
                    for (var m : slog.deriveMessages().messages()) {
                        if (m.role() != null && "USER".equals(m.role().name())) {
                            String t = m.content() == null ? "" : m.content().trim();
                            if (!t.isEmpty()) { title = t.length() > 40 ? t.substring(0, 40) + "…" : t; break; }
                        }
                    }
                    var frame = new java.util.LinkedHashMap<String, Object>();
                    frame.put("type", "session/projection");
                    frame.put("sessionId", sid.value());
                    frame.put("key", "title");
                    frame.put("value", title);
                    frame.put("seq", System.currentTimeMillis());
                    registry.sendMuxFrame(java.util.UUID.randomUUID().toString(), frame);
                }
            } catch (Exception e) {
                log.debug("pushAllSessionTitles skipped: {}", e.toString());
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            registry.unregister(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("downlink only"));
        }
    }
}
