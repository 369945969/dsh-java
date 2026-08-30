package com.deepseek.dsh.app.cli;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
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
 * CLI session-event sink: appends to SessionLog (shared with Web) and prints
 * to stdout (CLI-specific rendering). Replaces the direct {@code CliTurnObserver}
 * approach so the agent run phase also persists events.
 */
public final class CliEventSink implements TurnOrchestrator.SessionEventSink {

    private static final Logger log = LoggerFactory.getLogger(CliEventSink.class);

    private static final boolean TTY = System.console() != null;
    private static final String DIM = TTY ? "\033[2m" : "";
    private static final String GREEN = TTY ? "\033[32m" : "";
    private static final String RESET = TTY ? "\033[0m" : "";

    private final Sessions sessions;
    private final PrintStream out;
    private boolean inThink = false;
    private String pendingToolCall = null;

    public CliEventSink(Sessions sessions, PrintStream out) {
        this.sessions = sessions;
        this.out = new PrintStream(new BufferedOutputStream(out, 8192), false);
    }

    @Override
    public void emit(String sessionId, String eventType, Map<String, Object> data) {
        appendToLog(sessionId, eventType, data);
        render(eventType, data);
    }

    private void appendToLog(String sessionId, String eventType, Map<String, Object> data) {
        try {
            SessionLog slog = sessions.getOrCreate(SessionId.of(sessionId));
            String surfaceOp = isSurfaceMessage(eventType) ? "append" : null;
            SessionEvent appended = slog.append(eventType, data, surfaceOp);
            sessions.persist(appended);
        } catch (Exception e) {
            log.debug("cli sink append ({}): {}", eventType, e.toString());
        }
    }

    private static boolean isSurfaceMessage(String eventType) {
        return "user/message".equals(eventType) || "assistant/message".equals(eventType);
    }

    @SuppressWarnings("unchecked")
    private void render(String type, Map<String, Object> data) {
        switch (type) {
            case "request/header" -> {
                out.print(DIM + "  [系统提示词已注入]" + RESET);
                out.println();
                out.flush();
            }
            case "request/context" -> {
                out.print(DIM + "  [上下文已注入]" + RESET);
                out.println();
                out.flush();
            }
            case "turn/start" -> {
                Object turn = data.get("turn");
                out.print(DIM + "  ── Turn " + turn + " ──" + RESET);
                out.println();
                out.flush();
            }
            case "user/message" -> {
                if (isContextMessage(data)) {
                    out.print(DIM + "  [上下文注入] " + extractContextLabel(data) + RESET);
                    out.println();
                    out.flush();
                } else {
                    out.print(DIM + "  [用户] " + extractMessageText(data) + RESET);
                    out.println();
                    out.println();
                    out.flush();
                }
            }
            case "assistant/chunk" -> {
                if (data.get("chunk") instanceof Map<?, ?> chunk) {
                    String chunkType = String.valueOf(chunk.get("type"));
                    String text = String.valueOf(chunk.get("text"));
                    if ("reasoning-delta".equals(chunkType)) {
                        if (!inThink) { out.print(DIM); out.println("-- think ----------------"); inThink = true; }
                        out.print(text);
                    } else {
                        if (inThink) { out.println(); out.println("------------------------"); out.print(RESET); inThink = false; }
                        out.print(text);
                    }
                    out.flush();
                }
            }
            case "assistant/message" -> {
                if (inThink) { out.println(); out.println("------------------------"); out.print(RESET); inThink = false; }
                out.println();
                out.println();
                out.flush();
            }
            case "tool/call" -> {
                closeThinkIfNeeded();
                String name = String.valueOf(data.getOrDefault("name", ""));
                String args = String.valueOf(data.getOrDefault("arguments", ""));
                pendingToolCall = name + (args.isEmpty() ? "" : ": " + extractSummary(args));
            }
            case "tool/result" -> {
                closeThinkIfNeeded();
                if (pendingToolCall != null) {
                    out.print(GREEN + "✓" + RESET + DIM + " " + pendingToolCall + RESET);
                    out.println();
                    out.flush();
                    pendingToolCall = null;
                } else {
                    out.print(GREEN);
                    out.println("  ✓");
                    out.print(RESET);
                    out.flush();
                }
            }
            case "step/end", "turn/end" -> {
                closeThinkIfNeeded();
                if (pendingToolCall != null) {
                    out.print(DIM + "> " + pendingToolCall + RESET);
                    out.println();
                    out.flush();
                    pendingToolCall = null;
                }
            }
            default -> { }
        }
    }

    private void closeThinkIfNeeded() {
        if (inThink) {
            out.println();
            out.println("------------------------");
            out.print(RESET);
            inThink = false;
            out.flush();
        }
    }

    private static boolean isContextMessage(Map<String, Object> data) {
        if (data.get("source") instanceof Map<?, ?> src) {
            return "plugin".equals(src.get("kind"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
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

    private static final String[] SUMMARY_FIELDS = {"command", "input", "pattern", "query", "path", "file_path", "url", "task", "directory", "glob", "action"};

    private static String extractSummary(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            for (String field : SUMMARY_FIELDS) {
                var v = node.get(field);
                if (v != null && v.isTextual()) {
                    String s = v.asText();
                    return s.length() > 120 ? s.substring(0, 120) + "…" : s;
                }
            }
        } catch (Exception ignored) { }
        return "";
    }
}
