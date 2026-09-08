package com.deepseek.dsh.session.log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 会话日志 —— 仅追加的 wire 事件流，存储与浏览器一致的格式。
 *
 * <p>核心不变式：<b>"模型可见 ⟺ 已记录"</b>。每个事件都有唯一递增 seq，
 * 与 live event 和 snapshot cursor 完全一致。
 *
 * <p>{@link #deriveMessages()} 从 wire 事件重建模型可见的 ChatMessage 列表。
 *
 * <p>设计模式：事件溯源（Event Sourcing）聚合根 + 投影（Projection）。
 */
public final class SessionLog {

    private final SessionId sessionId;
    private final List<SessionEvent> events = new ArrayList<>();
    private final AtomicLong nextSeq = new AtomicLong(0);
    /** 会话所属应用（默认 "default"，由 SessionAuth 从 header/env 注入）。 */
    private String appid = "default";
    /** 会话所属用户（默认 "" 匿名，由 SessionAuth 从 header/env 注入）。 */
    private String userid = "";
    /** 推理标记（true/false/auto，由 SessionAuth 从 header/env 注入；auto=模型默认）。 */
    private String reasoning = "auto";
    /** 本会话使用的模型 id（由 SessionAuth 从 header/env 注入；空=用活跃档案模型）。 */
    private String modelId = "";
    /** 所属工作区 id（由 SessionAuth 从 header/env 注入；空=不属于任何工作区）。 */
    private String workspaceId = "";
    /** 累计输入 token（prompt tokens，跨所有 turn）。 */
    private long inputTokens = 0;
    /** 累计输出 token（completion tokens，跨所有 turn）。 */
    private long outputTokens = 0;

    public SessionLog(SessionId sessionId) {
        this.sessionId = sessionId;
    }

    public SessionId sessionId() { return sessionId; }
    public int size() { return events.size(); }

    /** 所属应用（appid）。 */
    public String appid() { return appid; }
    /** 所属用户（userid）。 */
    public String userid() { return userid; }
    /** 推理标记（true/false/auto）。 */
    public String reasoning() { return reasoning; }
    /** 本会话使用的模型 id（空=活跃档案模型）。 */
    public String modelId() { return modelId; }
    /** 所属工作区 id（空=不属于任何工作区）。 */
    public String workspaceId() { return workspaceId; }
    /** 由 SessionManager 在创建时从 SessionAuth 盖章（含 reasoning/modelId/workspaceId）。 */
    public void setAuth(String appid, String userid, String reasoning, String modelId, String workspaceId) {
        this.appid = appid == null ? "default" : appid;
        this.userid = userid == null ? "" : userid;
        this.reasoning = reasoning == null || reasoning.isBlank() ? "auto" : reasoning;
        this.modelId = modelId == null ? "" : modelId;
        this.workspaceId = workspaceId == null ? "" : workspaceId;
    }
    /** 累计输入 token。 */
    public long inputTokens() { return inputTokens; }
    /** 累计输出 token。 */
    public long outputTokens() { return outputTokens; }
    /** 本会话累计总 token（input+output），供客户端判断何时手动触发压缩。 */
    public long totalSessionTokens() { return inputTokens + outputTokens; }
    /** 累加本 turn 的 token 用量（由 web/ws 层在 turn 结束后从 TokenMeterService delta 盖章）。 */
    public synchronized void addTokens(long in, long out) {
        inputTokens += Math.max(0, in);
        outputTokens += Math.max(0, out);
    }

    public long lastSeq() {
        return events.isEmpty() ? -1 : events.get(events.size() - 1).seq();
    }

    public synchronized List<SessionEvent> events() {
        return List.copyOf(events);
    }

    public synchronized SessionEvent append(String type, Map<String, Object> data) {
        return append(type, data, null);
    }

    public synchronized SessionEvent append(String type, Map<String, Object> data, String surfaceOp) {
        long seq = nextSeq.getAndIncrement();
        SessionEvent e = new SessionEvent(seq, sessionId, type, data,
                System.currentTimeMillis(), surfaceOp, SessionEvent.Lineage.root());
        events.add(e);
        return e;
    }

    public synchronized List<SessionEvent> snapshot() {
        return List.copyOf(events);
    }

    @SuppressWarnings("unchecked")
    public synchronized SessionEvent.Projection deriveMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        List<ChatMessage.ToolCall> pendingCalls = new ArrayList<>();

        for (SessionEvent e : events) {
            switch (e.type()) {
                case "user/message" -> {
                    flushAssistant(messages, pendingCalls);
                    pendingCalls.clear();
                    String text = extractText(e.data());
                    if (text != null && !text.isEmpty()) {
                        messages.add(ChatMessage.user(text));
                    }
                }
                case "assistant/message" -> {
                    flushAssistant(messages, pendingCalls);
                    pendingCalls.clear();
                    Map<String, Object> msg = (Map<String, Object>) e.data().get("message");
                    if (msg != null) {
                        String content = extractContentText(msg.get("content"));
                        List<?> toolCalls = (List<?>) msg.get("tool_calls");
                        if (toolCalls != null) {
                            for (Object tc : toolCalls) {
                                if (tc instanceof Map<?, ?> m) {
                                    Map<?, ?> fn = (Map<?, ?>) m.get("function");
                                    if (fn != null) {
                                        pendingCalls.add(new ChatMessage.ToolCall(
                                                String.valueOf(m.get("id")),
                                                String.valueOf(fn.get("name")),
                                                String.valueOf(fn.get("arguments"))));
                                    }
                                }
                            }
                        }
                        if (content != null && !content.isEmpty()) {
                            messages.add(ChatMessage.assistant(content, List.copyOf(pendingCalls)));
                            pendingCalls.clear();
                        }
                    }
                }
                case "tool/call" -> {
                    String callId = str(e.data(), "callId");
                    String name = str(e.data(), "name");
                    String args = str(e.data(), "arguments");
                    pendingCalls.add(new ChatMessage.ToolCall(callId, name, args));
                }
                case "tool/result" -> {
                    flushAssistant(messages, pendingCalls);
                    pendingCalls.clear();
                    Map<String, Object> msg = (Map<String, Object>) e.data().get("message");
                    if (msg != null) {
                        String text = extractContentText(msg.get("content"));
                        String callId = "";
                        Object src = msg.get("source");
                        if (src instanceof Map<?, ?> s) {
                            callId = str(s, "callId");
                        }
                        if (text != null) {
                            messages.add(ChatMessage.tool(callId, text));
                        }
                    }
                }
                default -> { }
            }
        }
        flushAssistant(messages, pendingCalls);
        return new SessionEvent.Projection(List.copyOf(messages), lastSeq());
    }

    private void flushAssistant(List<ChatMessage> messages, List<ChatMessage.ToolCall> calls) {
        if (!calls.isEmpty()) {
            messages.add(ChatMessage.assistant("", List.copyOf(calls)));
            calls.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> data) {
        Object content = data.get("content");
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        if (content instanceof String s) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractContentText(Object content) {
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> p) {
                    if ("text".equals(p.get("type")) && p.get("text") instanceof String t) {
                        sb.append(t);
                    } else if ("tool-result".equals(p.get("type"))) {
                        String nested = extractContentText(p.get("content"));
                        if (nested != null) sb.append(nested);
                    }
                }
            }
            return sb.toString();
        }
        if (content instanceof String s) return s;
        return null;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
