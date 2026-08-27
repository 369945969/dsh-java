package com.deepseek.dsh.web.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * apiproxy 两条下行 WebSocket 流注册 —— 对应原 Harness 的 mux/host downlinks。
 * 下行 only：接受连接并登记到 {@link ApiproxyDownlinkRegistry}（触发客户端 onOpen，越过连接握手）；
 * 客户端上行消息以 1008 关闭（对齐原行为）。帧推送由 registry 在 agent 运行时发起。
 */
@Configuration
public class ApiproxyWebSocketConfig implements WebSocketConfigurer {

    private final ApiproxyDownlinkRegistry registry;

    public ApiproxyWebSocketConfig(ApiproxyDownlinkRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new DownlinkHandler(this.registry), "/api/events.mux").setAllowedOrigins("*");
        registry.addHandler(new DownlinkHandler(this.registry), "/api/events.host").setAllowedOrigins("*");
    }

    static final class DownlinkHandler extends TextWebSocketHandler {
        private final ApiproxyDownlinkRegistry registry;
        DownlinkHandler(ApiproxyDownlinkRegistry registry) { this.registry = registry; }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            // 路径区分 mux/host（同一个 handler 类，按 URI 登记）
            if (session.getUri() != null && session.getUri().toString().contains("events.mux")) {
                registry.registerMux(session);
            } else {
                registry.registerHost(session);
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
