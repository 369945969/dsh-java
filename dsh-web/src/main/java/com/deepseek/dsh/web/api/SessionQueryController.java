package com.deepseek.dsh.web.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.meter.TokenMeterService;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.web.server.AgentContextHolder;

/**
 * 会话查询控制器 —— 供前端侧边栏会话列表与历史加载。
 *
 * <p>对应原 Harness 的 session 投影查询面：
 * <ul>
 *   <li>{@code GET /api/agent/sessions} —— 列出全部活跃会话（id、标题、消息数）。</li>
 *   <li>{@code GET /api/agent/sessions/{id}/messages} —— 投影为模型可见消息列表 + token 统计。</li>
 * </ul>
 *
 * <p>标题取首条用户消息文本前缀；无用户消息时回退为会话 id 前缀。
 *
 * <p>设计模式：前端控制器 + 读模型投影（CQRS 读端）。
 */
@RestController
@RequestMapping("/api/agent/sessions")
public class SessionQueryController {

    private static final Logger log = LoggerFactory.getLogger(SessionQueryController.class);
    private static final int TITLE_MAX = 40;

    private final AgentContextHolder holder;

    public SessionQueryController(AgentContextHolder holder) {
        this.holder = holder;
    }

    /** 列出全部活跃会话。 */
    @GetMapping
    public Map<String, Object> list() {
        Context ctx = holder.context();
        Sessions sessions = ctx.require(Sessions.class);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionId id : sessions.list()) {
            var opt = sessions.get(id);
            if (opt.isEmpty()) continue;
            SessionLog log = opt.get();
            SessionEvent.Projection p = log.deriveMessages();
            items.add(Map.of(
                    "sessionId", id.value(),
                    "title", titleOf(p.messages(), id),
                    "messageCount", p.messages().size(),
                    "createdAt", log.snapshot().isEmpty()
                            ? "" : String.valueOf(log.snapshot().get(0).time())));
        }
        long totalTokens = ctx.get(TokenMeterService.class).map(TokenMeterService::totalTokens).orElse(0L);
        return Map.of("sessions", items, "count", items.size(), "totalTokens", totalTokens);
    }

    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id) {
        Context ctx = holder.context();
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog slog = sessions.get(SessionId.of(id))
                .orElseGet(() -> sessions.getOrCreate(SessionId.of(id)));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SessionEvent e : slog.events()) {
            switch (e.type()) {
                case "user/message" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "user");
                    m.put("content", extractTextFromContent(e.data().get("content")));
                    messages.add(m);
                }
                case "assistant/message" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "assistant");
                    Object msgObj = e.data().get("message");
                    if (msgObj instanceof Map<?, ?> msg) {
                        m.put("content", extractTextFromContent(msg.get("content")));
                    }
                    messages.add(m);
                }
                case "tool/call" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool");
                    m.put("name", String.valueOf(e.data().getOrDefault("name", "")));
                    m.put("arguments", String.valueOf(e.data().getOrDefault("arguments", "{}")));
                    messages.add(m);
                }
                case "tool/result" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool_result");
                    Object msgObj = e.data().get("message");
                    if (msgObj instanceof Map<?, ?> msg) {
                        m.put("content", extractTextFromContent(msg.get("content")));
                    }
                    messages.add(m);
                }
                default -> {}
            }
        }
        long totalTokens = ctx.get(TokenMeterService.class).map(TokenMeterService::totalTokens).orElse(0L);
        return Map.of(
                "sessionId", id,
                "messages", messages,
                "totalTokens", totalTokens,
                "lastSeq", slog.lastSeq());
    }

    private static String extractTextFromContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof java.util.List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof java.util.Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String toolArgsJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        try { return MAPPER.writeValueAsString(args); }
        catch (Exception ex) { return "{}"; }
    }

    private static String titleOf(List<ChatMessage> messages, SessionId id) {
        for (ChatMessage m : messages) {
            if (m.role() == ChatMessage.Role.USER && m.content() != null && !m.content().isBlank()) {
                String t = m.content().trim().replaceAll("\\s+", " ");
                return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX) + "…";
            }
        }
        return id.value().length() <= 12 ? id.value() : id.value().substring(0, 12) + "…";
    }
}
