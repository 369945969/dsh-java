package com.deepseek.dsh.web.server;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Remote mux WebSocket —— 复刻 harness 0.1.2 的
 * {@code /api/remote.mux}（{@code packages/api/gateway/src/stream-protocol.ts}）。
 *
 * <p>客户端通过此 WebSocket 多路复用所有 Remote 流。最关键的流是
 * {@code $events}：打开后第一帧必须是 {@code {type:"ready", clientId, host:{home}}}，
 * 客户端据此进入 connected 状态；此后各 Cordis 事件以
 * {@code {type:"item", streamId, value:{type:"emit", event, args}}} 帧投递。
 *
 * <p>帧协议（{@code stream-protocol.ts:242-263}）：
 * <ul>
 *   <li>客户端→服务端：{@code open}（streamId/endpoint/payload）或 {@code cancel}（streamId）</li>
 *   <li>服务端→客户端：{@code item}（streamId/value）或 {@code end}（streamId）或 {@code error}</li>
 * </ul>
 */
@Configuration
public class RemoteMuxWebSocketConfig implements WebSocketConfigurer {

    private final RemoteMuxRegistry registry;
    private final WorkspaceRegistry workspaces;

    public RemoteMuxWebSocketConfig(RemoteMuxRegistry registry, WorkspaceRegistry workspaces) {
        this.registry = registry;
        this.workspaces = workspaces;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RemoteMuxHandler(this.registry, this.workspaces), "/api/remote.mux")
                .setAllowedOrigins("*");
    }

    static class RemoteMuxHandler extends TextWebSocketHandler {
        private static final Logger log = LoggerFactory.getLogger(RemoteMuxHandler.class);
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicLong clientIdSeq = new AtomicLong();
        private final RemoteMuxRegistry registry;
        private final WorkspaceRegistry workspaces;

        RemoteMuxHandler(RemoteMuxRegistry registry, WorkspaceRegistry workspaces) {
            this.registry = registry;
            this.workspaces = workspaces;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            log.info("remote.mux connected: {}", session.getId());
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
            String payload = message.getPayload();
            Map<?, ?> msg;
            try {
                msg = mapper.readValue(payload, Map.class);
            } catch (Exception e) {
                log.warn("remote.mux: invalid JSON from {}: {}", session.getId(), e.getMessage());
                return;
            }
            String type = (String) msg.get("type");
            if ("open".equals(type)) {
                String streamId = (String) msg.get("streamId");
                String endpoint = (String) msg.get("endpoint");
                if (streamId == null || endpoint == null) return;
                handleStreamOpen(session, streamId, endpoint, msg.get("payload"));
            } else if ("cancel".equals(type)) {
                String streamId = (String) msg.get("streamId");
                if (streamId != null) sendEnd(session, streamId);
            }
        }

    private void handleStreamOpen(WebSocketSession session, String streamId, String endpoint, Object payload) throws IOException {
        log.info("remote.mux stream open: endpoint={} streamId={}", endpoint, streamId);
        if ("$events".equals(endpoint)) {
            String clientId = "c" + clientIdSeq.incrementAndGet();
            String home = System.getProperty("user.home");
            sendItem(session, streamId, Map.of(
                    "type", "ready",
                    "clientId", clientId,
                    "host", Map.of("home", home)));
            registry.registerEvents(session.getId(), session, streamId);
        } else if ("session/follow".equals(endpoint)) {
            handleSessionFollow(session, streamId, payload);
        } else if ("workspace/follow".equals(endpoint)) {
            handleWorkspaceFollow(session, streamId, payload);
        } else if ("session/control".equals(endpoint)) {
            var baseline = registry.buildControlBaseline();
            sendItem(session, streamId, Map.of("type", "baseline", "value",
                    baseline != null ? baseline : Map.of("queues", Map.of(), "jobs", Map.of(), "projections", Map.of())));
            registry.registerControl(session, streamId);
        }
    }

    private void handleSessionFollow(WebSocketSession session, String streamId, Object payload) throws IOException {
        String sessionId = null;
        log.info("session/follow: rawPayload={}", payload);
        if (payload instanceof Map<?, ?> pm && pm.get("args") instanceof Map<?, ?> args) {
            log.info("session/follow: argsKeys={}", args.keySet());
            if (args.get("address") instanceof Map<?, ?> addr) {
                sessionId = addr.get("sessionId") instanceof String s ? s : null;
            } else if (args.get("request") instanceof Map<?, ?> req && req.get("address") instanceof Map<?, ?> addr) {
                sessionId = addr.get("sessionId") instanceof String s ? s : null;
            }
        }
        log.info("session/follow: sessionId={}", sessionId);
        if (sessionId == null || sessionId.isEmpty()) return;
        registry.registerFollow(sessionId, session, streamId);
        var snapshot = registry.buildFollowSnapshot(sessionId);
        if (snapshot != null) {
            registry.sendFollowSnapshot(session, streamId,
                    snapshot.cursor(), snapshot.records(), snapshot.hasMore(), snapshot.projections(),
                    snapshot.header());
        }
    }

    private void handleWorkspaceFollow(WebSocketSession session, String streamId, Object payload) throws IOException {
        List<?> items = workspaces.list();
        List<String> archived = workspaces.archivedSessionIds();
        Map<String, Object> baseline = Map.of("items", items, "archivedSessionIds", archived);
        sendItem(session, streamId, Map.of("type", "baseline", "value", baseline));
        registry.registerWorkspaceFollow(session, streamId);
    }

        void sendItem(WebSocketSession session, String streamId, Object value) throws IOException {
            if (!session.isOpen()) return;
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "item",
                    "streamId", streamId,
                    "value", value))));
        }

        void sendEnd(WebSocketSession session, String streamId) throws IOException {
            if (!session.isOpen()) return;
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "end",
                    "streamId", streamId))));
        }

        void sendEmit(WebSocketSession session, String streamId, String event, Object[] args) throws IOException {
            sendItem(session, streamId, Map.of(
                    "type", "emit",
                    "event", event,
                    "args", args));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            registry.unregister(session.getId());
            log.debug("remote.mux closed: {} ({})", session.getId(), status);
        }
    }
}
