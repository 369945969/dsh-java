package com.deepseek.dsh.web.server;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * apiproxy 下行流注册表 —— 跟踪当前浏览器打开的 mux/host WebSocket 会话，
 * 供 {@code session.prompt} 等一元 RPC 在 agent 运行时向其推送 {@code ServerRequest} 帧。
 *
 * <p>原 Harness 中两条下行流由 host 在事件源（agent loop / session 投影）触发时推送；
 * 此处 Java 侧桥接：agent 流式 chunk → assistant/chunk 帧 → mux WS。
 *
 * <p>设计模式：观察者注册表（运行时帧分发）。
 */
@Component
public class ApiproxyDownlinkRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiproxyDownlinkRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<WebSocketSession> muxSessions = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketSession> hostSessions = ConcurrentHashMap.newKeySet();

    public void registerMux(WebSocketSession session) { muxSessions.add(session); }
    public void registerHost(WebSocketSession session) { hostSessions.add(session); }
    public void unregister(WebSocketSession session) { muxSessions.remove(session); hostSessions.remove(session); }

    /** 向所有 mux 下行流推送一个 ServerRequest 帧（payload 为 MuxFrame）。 */
    public void sendMuxFrame(String rpcId, Object muxFrame) {
        send(muxSessions, rpcId, muxFrame);
    }

    /** 向所有 host 下行流推送一个 ServerRequest 帧（payload 为 HostFrame）。 */
    public void sendHostFrame(String rpcId, Object hostFrame) {
        send(hostSessions, rpcId, hostFrame);
    }

    private void send(Set<WebSocketSession> sessions, String rpcId, Object payload) {
        if (sessions.isEmpty()) { log.debug("下行流无连接，丢弃帧 rpcId={}", rpcId); return; }
        var full = new java.util.LinkedHashMap<String, Object>();
        full.put("type", "server-request");
        full.put("rpcId", rpcId);
        full.put("method", payload instanceof java.util.Map<?,?> m ? String.valueOf(m.get("type")) : "unknown");
        full.put("payload", payload);
        try {
            String json = MAPPER.writeValueAsString(full);
            TextMessage msg = new TextMessage(json);
            for (WebSocketSession s : new LinkedHashSet<>(sessions)) {
                if (s.isOpen()) { try { s.sendMessage(msg); } catch (Exception e) { log.debug("mux 帧发送失败: {}", e.toString()); } }
                else sessions.remove(s);
            }
        } catch (Exception e) {
            log.warn("帧序列化失败: {}", e.toString());
        }
    }
}
