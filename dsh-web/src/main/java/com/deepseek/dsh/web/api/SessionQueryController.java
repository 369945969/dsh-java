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
                            ? "" : log.snapshot().get(0).createdAt().toString()));
        }
        long totalTokens = ctx.get(TokenMeterService.class).map(TokenMeterService::totalTokens).orElse(0L);
        return Map.of("sessions", items, "count", items.size(), "totalTokens", totalTokens);
    }

    /** 投影某会话为消息列表（含思维链 reasoning + 工具调用/结果），供前端刷新后重放。 */
    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id) {
        Context ctx = holder.context();
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog slog = sessions.get(SessionId.of(id))
                .orElseGet(() -> sessions.getOrCreate(SessionId.of(id)));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SessionEvent e : slog.events()) {
            switch (e.type()) {
                case USER_MESSAGE -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "user");
                    m.put("content", e.payload().text() == null ? "" : e.payload().text());
                    messages.add(m);
                }
                case ASSISTANT_MESSAGE -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "assistant");
                    m.put("content", e.payload().text() == null ? "" : e.payload().text());
                    String reasoning = e.payload().reasoning();
                    if (reasoning != null && !reasoning.isBlank()) m.put("reasoning", reasoning);
                    messages.add(m);
                }
                case TOOL_CALL -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool");
                    m.put("name", e.payload().toolName() == null ? "" : e.payload().toolName());
                    m.put("arguments", toolArgsJson(e.payload().structured()));
                    messages.add(m);
                }
                case TOOL_RESULT -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", "tool_result");
                    m.put("content", e.payload().text() == null ? "" : e.payload().text());
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
