package com.agentscope.ws;

import com.agentscope.config.Config;
import com.agentscope.web.WebServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.framing.CloseFrame;

import java.net.InetSocketAddress;
import java.util.*;

public class WsServer extends WebSocketServer {
    private static final ObjectMapper M = new ObjectMapper();
    private final HarnessAgent agent;
    private final Config cfg;

    public WsServer(int port, HarnessAgent agent, Config cfg) {
        super(new InetSocketAddress(port + 1));
        this.agent = agent;
        this.cfg = cfg;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String cookie = handshake.getFieldValue("cookie");
        if (cookie == null || !cookie.contains("dsh-auth=" + cfg.token)) {
            send(conn, frame("error", "", "unauthorized: token required"));
            conn.close(1008, "unauthorized"); // 1008 = Policy Violation
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = M.readValue(message, Map.class);
            String action = (String) req.getOrDefault("action", "");
            String sid = (String) req.getOrDefault("sessionId", UUID.randomUUID().toString());
            String uid = (String) req.getOrDefault("userId", "default");

            if ("prompt".equals(action)) {
                String msg = (String) req.getOrDefault("message", "");
                // 按请求参数注入系统提示词 / skill 提示词（中间件从 RuntimeContext 读取）
                String systemPrompt = (String) req.get("systemPrompt");
                String skillPrompt = (String) req.get("skillPrompt");
                send(conn, frame("session", sid, sid));
                var ctxBuilder = RuntimeContext.builder()
                        .sessionId(sid).userId(uid);
                if (systemPrompt != null && !systemPrompt.isBlank())
                    ctxBuilder.put("customSysPrompt", systemPrompt);
                if (skillPrompt != null && !skillPrompt.isBlank())
                    ctxBuilder.put("skillPrompt", skillPrompt);
                RuntimeContext ctx = ctxBuilder.build();
                agent.streamEvents(new UserMessage(msg), ctx)
                    .doOnNext(event -> {
                        try {
                            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                                String delta = ((TextBlockDeltaEvent) event).getDelta();
                                if (delta != null && !delta.isEmpty())
                                    send(conn, frame("delta", sid, delta));
                            } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                                String name = ((ToolCallStartEvent) event).getToolCallName();
                                send(conn, frame("tool_call", sid, name));
                            }
                        } catch (Exception ignored) {}
                    })
                    .doOnError(e -> send(conn, frame("error", sid, e.getMessage())))
                    .doOnComplete(() -> send(conn, frame("done", sid, "[DONE]")))
                    .subscribe();
            } else if ("cancel".equals(action)) {
                send(conn, frame("cancelled", sid, ""));
            } else {
                send(conn, frame("error", sid, "unknown action: " + action));
            }
        } catch (Exception e) {
            try { send(conn, frame("error", "", e.getMessage())); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {}

    @Override
    public void onStart() {}

    void send(WebSocket conn, String json) { conn.send(json); }

    String frame(String event, String sid, String data) {
        return "{\"event\":\"" + event + "\",\"sessionId\":\"" + sid + "\",\"data\":\"" +
               (data == null ? "" : data.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"}";
    }
}
