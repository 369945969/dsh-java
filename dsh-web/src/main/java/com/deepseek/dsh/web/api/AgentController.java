package com.deepseek.dsh.web.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.meter.TokenMeterService;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.web.server.AgentContextHolder;

import java.util.UUID;

/**
 * Agent API 控制器 —— 对应原 Harness 的 {@code apiproxy} 网关。
 *
 * <p>提供发送消息、查询历史、token 统计等 REST 端点。
 *
 * <p>设计模式：前端控制器（Front Controller）+ 依赖注入。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentContextHolder holder;

    public AgentController(AgentContextHolder holder) {
        this.holder = holder;
    }

    /**
     * 发送一条用户消息并返回 agent 回复。
     */
    @PostMapping("/send")
    public SendMessageResponse send(@RequestBody SendMessageRequest request) {
        try {
            Context ctx = holder.context();
            Agent agent = holder.agent();
            Sessions sessions = ctx.require(Sessions.class);

            SessionId sessionId = request.sessionId() == null
                    ? SessionId.of(UUID.randomUUID().toString())
                    : SessionId.of(request.sessionId());
            ScopeKey scopeKey = ScopeKey.random();

            String reply = agent.run(sessionId, scopeKey, ctx, request.message());
            SessionLog sessionLog = sessions.getOrCreate(sessionId);
            var projection = sessionLog.deriveMessages();

            long totalTokens = ctx.get(TokenMeterService.class)
                    .map(TokenMeterService::totalTokens).orElse(0L);

            return new SendMessageResponse(sessionId.value(), reply,
                    projection.messages(), totalTokens);
        } catch (Exception e) {
            log.error("Message processing failed", e);
            throw new RuntimeException("agent processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * 健康检查端点。
     */
    @GetMapping("/health")
    public String health() {
        return "{\"status\":\"ok\"}";
    }
}
