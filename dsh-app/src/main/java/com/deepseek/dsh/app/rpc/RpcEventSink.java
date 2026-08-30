package com.deepseek.dsh.app.rpc;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.web.TurnOrchestrator;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * RPC session-event sink: appends to SessionLog (shared with Web/CLI) and
 * logs injection/turn events to stderr via SLF4J (stdout reserved for JSON-RPC).
 */
public final class RpcEventSink implements TurnOrchestrator.SessionEventSink {

    private static final Logger log = LoggerFactory.getLogger("dsh.rpc.events");

    private final Sessions sessions;

    public RpcEventSink(Sessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void emit(String sessionId, String eventType, Map<String, Object> data) {
        appendToLog(sessionId, eventType, data);
        logEvent(eventType, data);
    }

    private void appendToLog(String sessionId, String eventType, Map<String, Object> data) {
        try {
            SessionLog slog = sessions.getOrCreate(SessionId.of(sessionId));
            String surfaceOp = isSurfaceMessage(eventType) ? "append" : null;
            SessionEvent appended = slog.append(eventType, data, surfaceOp);
            sessions.persist(appended);
        } catch (Exception e) {
            log.debug("rpc sink append ({}): {}", eventType, e.toString());
        }
    }

    private static boolean isSurfaceMessage(String eventType) {
        return "user/message".equals(eventType) || "assistant/message".equals(eventType);
    }

    private void logEvent(String type, Map<String, Object> data) {
        switch (type) {
            case "request/header" -> log.info("[注入] 系统提示词");
            case "request/context" -> log.info("[注入] 上下文");
            case "turn/start" -> log.info("[Turn {}] 开始", data.get("turn"));
            case "user/message" -> {
                if (isContextMessage(data)) {
                    log.info("[注入] 上下文: {}", extractContextLabel(data));
                } else {
                    log.info("[用户] {}", extractMessageText(data));
                }
            }
            case "step/start" -> log.info("[Step] turn={} step={}", data.get("turn"), data.get("step"));
            case "step/end" -> log.info("[Step] 结束 turn={} step={}", data.get("turn"), data.get("step"));
            case "assistant/message" -> log.info("[助手] 消息已定稿");
            case "tool/call" -> log.info("[工具调用] {} {}", data.get("name"), data.getOrDefault("arguments", ""));
            case "tool/result" -> log.info("[工具结果] callId={}", data.getOrDefault("callId", ""));
            case "turn/end" -> log.info("[Turn {}] 结束: {}", data.get("turn"),
                    data.get("reason") instanceof Map<?, ?> r ? r.get("kind") : "complete");
            default -> { }
        }
    }

    private static boolean isContextMessage(Map<String, Object> data) {
        if (data.get("source") instanceof Map<?, ?> src) {
            return "plugin".equals(src.get("kind"));
        }
        return false;
    }

    private static String extractContextLabel(Map<String, Object> data) {
        if (data.get("source") instanceof Map<?, ?> src && src.get("plugin") instanceof String plugin) {
            if ("dsh-context".equals(plugin)) return "AGENTS.md";
            if ("dsh-system-prompt".equals(plugin)) return "runtime context";
            if ("dsh-skill".equals(plugin)) return "skills";
            return plugin;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractMessageText(Map<String, Object> data) {
        if (data.get("content") instanceof List<?> parts && !parts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            String s = sb.toString().trim();
            return s.length() > 100 ? s.substring(0, 100) + "…" : s;
        }
        return "";
    }
}
