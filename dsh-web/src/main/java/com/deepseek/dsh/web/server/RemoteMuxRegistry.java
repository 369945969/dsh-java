package com.deepseek.dsh.web.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 活跃 WS mux 流注册表 —— 桥接 agent 事件生产方与 WS mux 客户端。
 *
 * <p>支持两类流：
 * <ul>
 *   <li><b>$events 流</b>（全局）：{@link #broadcastEmit} 向所有连接投递
 *       {@code {type:"item", streamId, value:{type:"emit", event, args}}} 帧；</li>
 *   <li><b>session/follow 流</b>（按会话）：{@link #broadcastFollowEvent} 向指定会话的
 *       follow 流投递 {@code {type:"item", streamId, value:{type:"event", event}}} 帧，
 *       以及快照帧 {@code {type:"snapshot", cursor, records, hasMore, projections}}。</li>
 * </ul>
 */
@Component
public class RemoteMuxRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteMuxRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private record StreamEntry(WebSocketSession session, String streamId) {}
    private final Map<String, StreamEntry> eventStreams = new ConcurrentHashMap<>();

    private record FollowEntry(WebSocketSession session, String streamId) {}
    private final Map<String, List<FollowEntry>> followStreams = new ConcurrentHashMap<>();

    private final List<FollowEntry> workspaceFollowers = new CopyOnWriteArrayList<>();
    private final List<FollowEntry> controlFollowers = new CopyOnWriteArrayList<>();

    private Function<String, FollowSnapshot> snapshotProvider;
    private java.util.function.Supplier<Map<String, Object>> controlBaselineProvider;

    public void setSnapshotProvider(Function<String, FollowSnapshot> provider) {
        this.snapshotProvider = provider;
    }

    public void setControlBaselineProvider(java.util.function.Supplier<Map<String, Object>> provider) {
        this.controlBaselineProvider = provider;
    }

    public Map<String, Object> buildControlBaseline() {
        return controlBaselineProvider != null ? controlBaselineProvider.get() : null;
    }

    public record FollowSnapshot(List<?> records, long cursor, boolean hasMore, Map<String, Object> projections, Map<String, Object> header) {}

    void registerEvents(String wsSessionId, WebSocketSession session, String streamId) {
        eventStreams.put(wsSessionId, new StreamEntry(session, streamId));
    }

    void registerFollow(String sessionId, WebSocketSession session, String streamId) {
        followStreams.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
                .add(new FollowEntry(session, streamId));
    }

    void registerWorkspaceFollow(WebSocketSession session, String streamId) {
        workspaceFollowers.add(new FollowEntry(session, streamId));
    }

    void registerControl(WebSocketSession session, String streamId) {
        controlFollowers.add(new FollowEntry(session, streamId));
    }

    void unregister(String wsSessionId) {
        eventStreams.remove(wsSessionId);
        followStreams.values().forEach(list -> list.removeIf(fe -> fe.session().getId().equals(wsSessionId)));
        workspaceFollowers.removeIf(fe -> fe.session().getId().equals(wsSessionId));
        controlFollowers.removeIf(fe -> fe.session().getId().equals(wsSessionId));
    }

    public void broadcastEmit(String event, Object[] args) {
        if (eventStreams.isEmpty()) { log.debug("broadcastEmit({}): no eventStreams", event); return; }
        log.debug("broadcastEmit({}): streams={}", event, eventStreams.size());
        for (var entry : eventStreams.values()) {
            WebSocketSession ws = entry.session();
            if (!ws.isOpen()) continue;
            try {
                String json = mapper.writeValueAsString(Map.of(
                        "type", "item",
                        "streamId", entry.streamId(),
                        "value", Map.of("type", "emit", "event", event, "args", args)));
                synchronized (ws) {
                    ws.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.debug("broadcastEmit({}): {}", event, e.toString());
            }
        }
    }

    public void broadcastFollowEvent(String sessionId, Map<String, Object> event) {
        List<FollowEntry> entries = followStreams.get(sessionId);
        if (entries == null || entries.isEmpty()) { log.debug("broadcastFollowEvent({}): no followStreams", sessionId); return; }
        log.debug("broadcastFollowEvent({}): streams={}", sessionId, entries.size());
        for (FollowEntry fe : entries) {
            WebSocketSession ws = fe.session();
            if (!ws.isOpen()) continue;
            try {
                String json = mapper.writeValueAsString(Map.of(
                        "type", "item",
                        "streamId", fe.streamId(),
                        "value", Map.of("type", "event", "event", event)));
                synchronized (ws) {
                    ws.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.debug("broadcastFollowEvent({}): {}", sessionId, e.toString());
            }
        }
    }

    public void broadcastWorkspaceFrame(Map<String, Object> frame) {
        broadcastToList(workspaceFollowers, frame);
    }

    public void broadcastControlFrame(Map<String, Object> frame) {
        broadcastToList(controlFollowers, frame);
    }

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Queue<String>> wsQueues = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean> wsSending = new java.util.concurrent.ConcurrentHashMap<>();

    private void sendToWs(WebSocketSession ws, String json) {
        String wsId = ws.getId();
        wsQueues.computeIfAbsent(wsId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(json);
        java.util.concurrent.atomic.AtomicBoolean sending = wsSending.computeIfAbsent(wsId, k -> new java.util.concurrent.atomic.AtomicBoolean(false));
        if (sending.compareAndSet(false, true)) {
            try {
                while (true) {
                    String msg = wsQueues.get(wsId).poll();
                    if (msg == null) break;
                    if (ws.isOpen()) ws.sendMessage(new TextMessage(msg));
                }
            } catch (Exception e) {
                log.debug("sendToWs: {}", e.toString());
            } finally {
                sending.set(false);
            }
        }
    }

    private void broadcastToList(List<FollowEntry> followers, Map<String, Object> frame) {
        if (followers.isEmpty()) return;
        for (FollowEntry fe : followers) {
            WebSocketSession ws = fe.session();
            if (!ws.isOpen()) continue;
            try {
                String json = mapper.writeValueAsString(Map.of("type", "item", "streamId", fe.streamId(), "value", frame));
                sendToWs(ws, json);
            } catch (Exception e) {
                log.debug("broadcastToList: {}", e.toString());
            }
        }
    }

    public FollowSnapshot buildFollowSnapshot(String sessionId) {
        return snapshotProvider != null ? snapshotProvider.apply(sessionId) : null;
    }

    void sendFollowSnapshot(WebSocketSession ws, String streamId,
                             long cursor, List<?> records, boolean hasMore, Map<String, Object> projections,
                             Map<String, Object> header) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "type", "item",
                    "streamId", streamId,
                    "value", Map.of(
                            "type", "snapshot",
                            "header", header != null ? header : Map.of(),
                            "cursor", cursor,
                            "records", records,
                            "hasMore", hasMore,
                            "projections", projections)));
            sendToWs(ws, json);
        } catch (Exception e) {
            log.debug("sendFollowSnapshot: {}", e.toString());
        }
    }
}
