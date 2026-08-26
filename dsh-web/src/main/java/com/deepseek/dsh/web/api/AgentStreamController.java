package com.deepseek.dsh.web.api;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.web.server.AgentContextHolder;

/**
 * Agent SSE 流式端点 —— 对应原 Harness apiproxy 的 SSE（{@code text/event-stream}）流式面。
 *
 * <p>模拟 Web 前端流式交互契约：前端 {@code POST /api/agent/stream} 后，
 * 服务端以 SSE 事件流返回 —— {@code session}（会话 id）→ 若干 {@code delta}（回复增量）→
 * {@code done}（{@code [DONE]} 哨兵）。前端据此逐帧渲染，与原 TS apiproxy 的
 * {@code \n\n} 帧 SSE 同形态。任意前端（自带 React 或用户自有前端）可对接此契约。
 *
 * <p>agent 回合在虚拟线程中执行，回复按行切分为 delta 事件流式下发。
 * 真正的 token 级流式需 agent loop 重构（见 README 已知限制）。
 *
 * <p>设计模式：观察者（事件流）+ 异步响应（SseEmitter）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamController.class);

    private final AgentContextHolder holder;

    public AgentStreamController(AgentContextHolder holder) {
        this.holder = holder;
    }

    /**
     * 流式对话：返回 SSE 事件流。
     *
     * @param request {@code sessionId}（可空则新建）与 {@code message}
     * @return SSE 事件流：session → delta* → done
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody SendMessageRequest request) {
        // 不超时（长连接，由 done/error 终结）
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            try {
                Context ctx = holder.context();
                Agent agent = holder.agent();
                SessionId sessionId = request.sessionId() == null || request.sessionId().isBlank()
                        ? SessionId.of(UUID.randomUUID().toString())
                        : SessionId.of(request.sessionId());
                ScopeKey scopeKey = ScopeKey.random();

                emitter.send(SseEmitter.event().name("session").data(sessionId.value()));

                String reply = agent.run(sessionId, scopeKey, ctx, request.message());

                // 按行切分，逐帧流式下发 delta
                String[] lines = reply == null ? new String[0] : reply.split("\n", -1);
                for (String line : lines) {
                    emitter.send(SseEmitter.event().name("delta").data(line));
                }

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 流式处理失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                    // 已关闭
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
