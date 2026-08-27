package com.deepseek.dsh.web.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;

/**
 * Agent WebSocket 端点处理器 —— {@code /ws/agent}。
 *
 * <p>双向实时通信，支持<b>并发处理多 session</b>：同一连接上可并发发起多个
 * 不同 sessionId 的对话，每个在独立虚拟线程运行，回复按 sessionId 标记交错下发；
 * 支持<b>流式</b>（session→delta*→done）与<b>会话取消</b>（cancel 中断运行中的回合）。
 *
 * <p>线协议（JSON 文本帧）：
 * <ul>
 *   <li>客户端 → 服务端：{@code {"action":"prompt","sessionId":"s1","message":"..."}}
 *       / {@code {"action":"cancel","sessionId":"s1"}}</li>
 *   <li>服务端 → 客户端：{@code {"event":"session|delta|done|cancelled|error","sessionId":"s1","data":"..."}}</li>
 * </ul>
 *
 * <p>设计模式：观察者（事件流）+ 命令（action 分发）+ 异步并发（虚拟线程）。
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentContextHolder holder;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    /** 每连接的运行中回合：sessionId → Future（取消句柄）。 */
    private final ConcurrentMap<WebSocketSession, ConcurrentMap<String, Future<?>>> running = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(AgentContextHolder holder) {
        this.holder = holder;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        running.put(session, new ConcurrentHashMap<>());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode req = mapper.readTree(message.getPayload());
        String action = req.path("action").asText("");
        String sid = req.path("sessionId").asText("");
        if (sid.isBlank()) {
            send(session, frame("error", "", "缺少 sessionId"));
            return;
        }
        if ("cancel".equals(action)) {
            Future<?> f = running.get(session).remove(sid);
            if (f != null) f.cancel(true);
            send(session, frame("cancelled", sid, null));
        } else if ("prompt".equals(action)) {
            String msg = req.path("message").asText("");
            Future<?> f = exec.submit(() -> runTurn(session, sid, msg));
            running.get(session).put(sid, f);
        } else {
            send(session, frame("error", sid, "未知 action: " + action));
        }
    }

    /** 运行一轮对话，流式下发事件；被取消（中断）时发 cancelled。 */
    private void runTurn(WebSocketSession session, String sid, String message) {
        try {
            Context ctx = holder.context();
            Agent agent = holder.agent();
            SessionId sessionId = SessionId.of(sid);
            send(session, frame("session", sid, null));
            StringBuilder acc = new StringBuilder();
            String reply = agent.streamChat(sessionId, ScopeKey.random(), ctx, message,
                    chunk -> {
                        acc.append(chunk);
                        send(session, frame("delta", sid, chunk));
                    });
            // 兜底：流式未产出时整段下发
            if (acc.length() == 0 && reply != null && !reply.isEmpty()) {
                send(session, frame("delta", sid, reply));
            }
            send(session, frame("done", sid, null));
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || isInterrupted(e)) {
                send(session, frame("cancelled", sid, null));
            } else {
                log.warn("WebSocket turn failed (sid={}): {}", sid, e.toString());
                send(session, frame("error", sid, e.getMessage()));
            }
        } finally {
            ConcurrentMap<String, Future<?>> m = running.get(session);
            if (m != null) m.remove(sid);
        }
    }

    private static boolean isInterrupted(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof InterruptedException) return true;
            c = c.getCause();
        }
        return false;
    }

    /** 同步发送（WebSocketSession 非线程安全，多虚拟线程并发写需串行化）。 */
    private void send(WebSocketSession session, ObjectNode frame) {
        if (session == null || !session.isOpen()) return;
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(frame.toString()));
            } catch (Exception e) {
                log.debug("WebSocket send failed: {}", e.toString());
            }
        }
    }

    private ObjectNode frame(String event, String sid, String data) {
        ObjectNode f = mapper.createObjectNode();
        f.put("event", event);
        f.put("sessionId", sid);
        if (data != null) f.put("data", data);
        return f;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConcurrentMap<String, Future<?>> m = running.remove(session);
        if (m != null) m.values().forEach(f -> f.cancel(true));
    }
}
