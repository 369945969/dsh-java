package com.deepseek.dsh.session.log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 会话日志 —— 仅追加的事件流，是 agent 状态的真相来源。
 *
 * <p>核心不变式：<b>"模型可见 ⟺ 已记录"</b>。模型请求的内容必须能从日志重建。
 * {@link #deriveMessages()} 将原始事件投影为模型可见的 {@link ChatMessage} 列表。
 *
 * <p>投影规则：
 * <ul>
 *   <li>{@code USER_MESSAGE} → 一条 user 消息。</li>
 *   <li>{@code ASSISTANT_CHUNK} → 合并为下一条 {@code ASSISTANT_MESSAGE} 的内容（分块保留供 UI 回放，但投影时折叠）。</li>
 *   <li>{@code ASSISTANT_MESSAGE} → 一条 assistant 消息（含可能的 tool_calls）。</li>
 *   <li>{@code TOOL_CALL} → 记录调用，与随后的 {@code TOOL_RESULT} 配对为一条 tool 消息。</li>
 *   <li>{@code COMMAND} → 不投影为模型消息（人类命令不经模型）。</li>
 * </ul>
 *
 * <p>设计模式：事件溯源（Event Sourcing）聚合根 + 投影（Projection）。
 */
public final class SessionLog {

    private final SessionId sessionId;
    private final List<SessionEvent> events = new ArrayList<>();
    private final AtomicLong nextSeq = new AtomicLong(0);

    public SessionLog(SessionId sessionId) {
        this.sessionId = sessionId;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    /** 当前已记录的事件总数。 */
    public int size() {
        return events.size();
    }

    /** 最后一条事件的序号（-1 表示空）。 */
    public long lastSeq() {
        return events.isEmpty() ? -1 : events.get(events.size() - 1).seq();
    }

    /** 追加一条事件（不可变）。 */
    public synchronized SessionEvent append(SessionEvent.Type type, SessionEvent.Payload payload) {
        long seq = nextSeq.getAndIncrement();
        SessionEvent e = new SessionEvent(
                seq, sessionId, type, payload,
                java.time.Instant.now(), SessionEvent.Lineage.root());
        events.add(e);
        return e;
    }

    /** 追加一条带谱系的事件（子会话/委派）。 */
    public synchronized SessionEvent append(SessionEvent.Type type, SessionEvent.Payload payload,
                                            SessionEvent.Lineage lineage) {
        long seq = nextSeq.getAndIncrement();
        SessionEvent e = new SessionEvent(seq, sessionId, type, payload,
                java.time.Instant.now(), lineage);
        events.add(e);
        return e;
    }

    /** 返回事件流的不可变快照。 */
    public synchronized List<SessionEvent> snapshot() {
        return List.copyOf(events);
    }

    /**
     * 将事件日志投影为模型可见的消息列表。
     * <p>这是 {@code deriveMessages()} 的等价实现：原始 {@code ASSISTANT_CHUNK}
     * 在投影时被折叠进随后的 {@code ASSISTANT_MESSAGE}，保证模型只看到完整的助手消息。
     */
    public synchronized SessionEvent.Projection deriveMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        // 待积累的助手分块文本
        StringBuilder pendingAssistant = new StringBuilder();
        // 按 toolCallId 收集结果
        Map<String, String> toolResults = new LinkedHashMap<>();
        // 待配对工具调用
        List<ChatMessage.ToolCall> pendingCalls = new ArrayList<>();

        for (SessionEvent e : events) {
            switch (e.type()) {
                case USER_MESSAGE -> {
                    flushAssistant(messages, pendingAssistant, pendingCalls);
                    pendingAssistant.setLength(0);
                    pendingCalls.clear();
                    messages.add(ChatMessage.user(e.payload().text()));
                }
                case ASSISTANT_CHUNK -> {
                    // 分块积累
                    pendingAssistant.append(e.payload().text());
                }
                case ASSISTANT_MESSAGE -> {
                    flushAssistant(messages, pendingAssistant, pendingCalls);
                    pendingAssistant.setLength(0);
                    pendingCalls.clear();
                    if (e.payload().text() != null) {
                        pendingAssistant.append(e.payload().text());
                    }
                    // ASSISTANT_MESSAGE 后可能立即接 TOOL_CALL，先 flush 文本
                    flushAssistant(messages, pendingAssistant, new ArrayList<>(pendingCalls));
                    pendingAssistant.setLength(0);
                }
                case TOOL_CALL -> {
                    // 如果有待积累的助手文本，先 flush 成一条 assistant 消息
                    flushAssistant(messages, pendingAssistant, List.of());
                    pendingAssistant.setLength(0);
                    pendingCalls.add(new ChatMessage.ToolCall(
                            e.payload().toolCallId(),
                            e.payload().toolName(),
                            toJson(e.payload().structured())));
                }
                case TOOL_RESULT -> {
                    flushAssistant(messages, pendingAssistant, pendingCalls);
                    pendingAssistant.setLength(0);
                    pendingCalls.clear();
                    messages.add(ChatMessage.tool(
                            e.payload().toolCallId(),
                            e.payload().text()));
                }
                case COMMAND, TURN_START, TURN_END, STEP_START, STEP_END -> {
                    // 这些不投影为模型消息
                }
            }
        }
        // 末尾若有未 flush 的助手文本
        flushAssistant(messages, pendingAssistant, pendingCalls);
        return new SessionEvent.Projection(List.copyOf(messages), lastSeq());
    }

    private void flushAssistant(List<ChatMessage> messages, StringBuilder pending,
                                List<ChatMessage.ToolCall> calls) {
        boolean hasText = pending.length() > 0;
        boolean hasCalls = !calls.isEmpty();
        if (hasText || hasCalls) {
            messages.add(ChatMessage.assistant(
                    hasText ? pending.toString() : "",
                    List.copyOf(calls)));
        }
    }

    private String toJson(Map<String, Object> structured) {
        if (structured == null || structured.isEmpty()) return "{}";
        // 简易 JSON 序列化（避免引入 Jackson 依赖到本类）
        return structured.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + jsonValue(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        return "\"" + v + "\"";
    }
}
