package com.deepseek.dsh.agent.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.agent.TurnObserver;
import com.deepseek.dsh.agent.loop.LoopHierarchy;
import com.deepseek.dsh.agent.state.ContinueDecision;
import com.deepseek.dsh.agent.state.StepResult;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.interaction.approval.ApprovalRequest;
import com.deepseek.dsh.interaction.approval.ApprovalResult;
import com.deepseek.dsh.interaction.approval.ApprovalService;
import com.deepseek.dsh.interaction.permission.PermissionPreset;
import com.deepseek.dsh.interaction.permission.PermissionService;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.adapter.LlmRequest;
import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.llm.meter.TokenMeterService;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.pipeline.ToolPipeline;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.Tools;

/**
 * ReAct 循环 —— 推理（Reasoning）+ 行动（Acting）循环引擎。
 *
 * <p>借鉴 AgentScope 的 ReAct loop 概念，结合原 Harness 的 turn/step 生命周期。
 * 采用<strong>模板方法</strong>定义循环骨架：{@link #runTurn} 编排 turn，
 * {@link #runStep} 编排单个 step（模型请求 → 工具执行 → 结果记录），
 * 子类可覆写各钩子定制行为。
 *
 * <p>循环不变式：<b>"模型可见 ⟺ 已记录"</b> —— 所有发送给模型的消息
 * 都从 {@link SessionLog} 投影得到。
 *
 * <p>设计模式：
 * <ul>
 *   <li>模板方法（{@link #runTurn} / {@link #runStep}）—— 定义循环骨架。</li>
 *   <li>状态机（{@link ContinueDecision}）—— 决定是否继续下一步。</li>
 *   <li>策略（注入的 {@link LlmModel} / {@link Tools}）—— 可替换的推理与行动。</li>
 * </ul>
 */
public class ReActAgentLoop implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentLoop.class);

    private final String name;
    private final String systemPrompt;
    private final LlmModel model;
    private final Tools tools;
    private final ToolPipeline pipeline;

    /** 当前 turn 的观察者（线程局部，并发安全）；由 {@link #runObserved} 设置。 */
    private final ThreadLocal<TurnObserver> observer = new ThreadLocal<>();

    public ReActAgentLoop(String name, String systemPrompt, LlmModel model, Tools tools) {
        this(name, systemPrompt, model, tools, new ToolPipeline(tools));
    }

    /**
     * 带预构建工具管线的构造器 —— 允许装配方注入中间件（如外溢策略、
     * 重复调用提醒、超时策略）。{@code tools} 仍用于装配工具 schema。
     */
    public ReActAgentLoop(String name, String systemPrompt, LlmModel model,
                           Tools tools, ToolPipeline pipeline) {
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.tools = tools;
        this.pipeline = pipeline;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * 带 {@link TurnObserver} 运行一个 turn：设置线程局部观察者后委托 {@link #run}，
     * 循环内触发 onAssistantMessage/onToolCall/onToolResult。结束后清除观察者。
     */
    @Override
    public String runObserved(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                               String userMessage, TurnObserver obs) throws Exception {
        observer.set(obs);
        try {
            return run(sessionId, scopeKey, ctx, userMessage);
        } finally {
            observer.remove();
        }
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 运行一个 turn（模板方法）。接收用户消息，驱动 ReAct 循环直到模型停止或达上限。
     */
    @Override
    public String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) throws Exception {
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog sessionLog = sessions.getOrCreate(sessionId);

        // turn/start —— 记录用户消息
        SessionEvent userEvent = sessionLog.append(SessionEvent.Type.USER_MESSAGE,
                SessionEvent.Payload.text(userMessage));
        sessions.persist(userEvent);

        // 迭代 step
        int maxSteps = LoopHierarchy.DEFAULT_MAX_STEPS;
        for (int stepNo = 0; stepNo < maxSteps; stepNo++) {
            StepResult step = runStep(sessionId, scopeKey, ctx, sessionLog);

            // 记录 token 用量
            ctx.get(TokenMeterService.class).ifPresent(m -> m.record(step.response()));

            // 判定是否继续
            ContinueDecision decision = decideContinue(step.response());
            if (decision != ContinueDecision.CONTINUE) {
                return step.response().content();
            }
        }
        log.warn("达到最大 step 数 {}，终止 turn", maxSteps);
        return "（已达最大步数限制）";
    }

    /**
     * 流式运行一个 turn —— 基于 {@link com.deepseek.dsh.llm.adapter.LlmModel#stream} 逐 token 下发。
     *
     * <p>纯对话流式（不装配工具，故不触发工具调用）—— 需要工具的回合请用 {@link #run}。
     * 记录用户/助手消息到会话日志，与 {@link #run} 一致。
     */
    @Override
    public String streamChat(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                             String userMessage, java.util.function.Consumer<String> deltaSink) throws Exception {
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog sessionLog = sessions.getOrCreate(sessionId);

        SessionEvent userEvent = sessionLog.append(SessionEvent.Type.USER_MESSAGE,
                SessionEvent.Payload.text(userMessage));
        sessions.persist(userEvent);

        // 装配消息（系统提示 + 历史 + 本轮用户消息），不带工具 schema
        SessionEvent.Projection projection = sessionLog.deriveMessages();
        List<ChatMessage> messages = new ArrayList<>(projection.messages());
        if (messages.isEmpty() || messages.get(0).role() != ChatMessage.Role.SYSTEM) {
            messages.add(0, ChatMessage.system(systemPrompt));
        }
        LlmRequest request = LlmRequest.of(messages, java.util.List.of(), modelIdentifier(ctx));

        // 逐 token 流式收集，每个 delta 即时下发
        StringBuilder acc = new StringBuilder();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        model.stream(request).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private java.util.concurrent.Flow.Subscription sub;
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { this.sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(com.deepseek.dsh.llm.adapter.LlmChunk c) {
                if (c.delta() != null && !c.delta().isEmpty()) {
                    acc.append(c.delta());
                    deltaSink.accept(c.delta());
                }
            }
            @Override public void onError(Throwable t) { err.set(t); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        done.await();
        if (err.get() != null) {
            throw err.get() instanceof Exception e ? e : new RuntimeException(err.get());
        }

        String content = acc.toString();
        SessionEvent assistantEvent = sessionLog.append(SessionEvent.Type.ASSISTANT_MESSAGE,
                SessionEvent.Payload.text(content));
        sessions.persist(assistantEvent);
        return content;
    }

    /**
     * 运行单个 step（模板方法）：装配提示 → 模型请求 → 工具执行 → 记录。
     */
    protected StepResult runStep(SessionId sessionId, ScopeKey scopeKey, Context ctx, SessionLog sessionLog) throws Exception {
        // 1. 从日志派生模型可见消息
        SessionEvent.Projection projection = sessionLog.deriveMessages();
        List<ChatMessage> messages = new ArrayList<>(projection.messages());
        // 前置系统提示
        if (messages.isEmpty() || messages.get(0).role() != ChatMessage.Role.SYSTEM) {
            messages.add(0, ChatMessage.system(systemPrompt));
        }

        // 2. 装配工具 schema
        LlmRequest request = LlmRequest.of(messages, tools.schemas(), modelIdentifier(ctx));

        // 3. 模型请求
        LlmResponse response = model.chat(request);

        // 4. 记录助手消息到日志
        SessionEvent assistantEvent = sessionLog.append(SessionEvent.Type.ASSISTANT_MESSAGE,
                SessionEvent.Payload.text(response.content()));
        ctx.require(Sessions.class).persist(assistantEvent);
        TurnObserver o = observer.get();
        if (o != null) o.onAssistantMessage(response.content());

        // 5. 执行工具调用（如果有）
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            for (ChatMessage.ToolCall tc : response.toolCalls()) {
                executeToolCall(sessionId, scopeKey, ctx, sessionLog, tc);
            }
        }

        return new StepResult(response, messages);
    }

    /**
     * 执行一次工具调用，经审批/权限把关后通过管线调用工具，结果记录到日志。
     */
    protected void executeToolCall(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                                   SessionLog sessionLog, ChatMessage.ToolCall tc) throws Exception {
        Sessions sessions = ctx.require(Sessions.class);

        // 权限检查
        PermissionService perm = ctx.get(PermissionService.class).orElse(null);
        if (perm != null) {
            PermissionPreset.Decision d = perm.check(tc.name(), scopeKey.value().toString());
            if (d == PermissionPreset.Decision.DENY) {
                SessionEvent denied = sessionLog.append(SessionEvent.Type.TOOL_RESULT,
                        SessionEvent.Payload.toolResult(tc.id(), "（已拒绝：权限不足）"));
                sessions.persist(denied);
                return;
            }
            if (d == PermissionPreset.Decision.ASK) {
                ApprovalService approval = ctx.get(ApprovalService.class).orElse(null);
                if (approval != null) {
                    ApprovalResult ar = approval.request(
                            ApprovalRequest.of("工具调用: " + tc.name(),
                                    "参数: " + tc.argumentsJson())).get();
                    if (!ar.approved()) {
                        SessionEvent denied = sessionLog.append(SessionEvent.Type.TOOL_RESULT,
                                SessionEvent.Payload.toolResult(tc.id(),
                                        "（用户拒绝: " + ar.feedback() + ")"));
                        sessions.persist(denied);
                        return;
                    }
                }
            }
        }

        // 记录工具调用事件
        Map<String, Object> args = parseArgs(tc.argumentsJson());
        SessionEvent callEvent = sessionLog.append(SessionEvent.Type.TOOL_CALL,
                SessionEvent.Payload.toolCall(tc.name(), tc.id(), args));
        sessions.persist(callEvent);

        // 经管线执行
        ToolContext toolCtx = new ToolContext(sessionId, scopeKey, ctx);
        TurnObserver o = observer.get();
        if (o != null) o.onToolCall(tc.id(), tc.name(), tc.argumentsJson());
        ToolExecutionResult result = pipeline.execute(
                new ToolExecutionRequest(tc.name(), tc.id(), args, toolCtx));

        // 记录结果
        SessionEvent resultEvent = sessionLog.append(SessionEvent.Type.TOOL_RESULT,
                SessionEvent.Payload.toolResult(tc.id(), result.text()));
        sessions.persist(resultEvent);
        if (o != null) o.onToolResult(tc.id(), result.text());
    }

    /**
     * 决定是否继续下一步（状态机）。
     * <p>若模型以 tool_calls 结束则继续；若 stop 则停止。
     */
    protected ContinueDecision decideContinue(LlmResponse response) {
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            return ContinueDecision.CONTINUE;
        }
        if ("stop".equalsIgnoreCase(response.finishReason())
                || response.finishReason() == null) {
            return ContinueDecision.STOP;
        }
        return ContinueDecision.CONTINUE;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String modelIdentifier(Context ctx) {
        // 默认使用 DeepSeek chat；可被配置覆盖
        return ctx.get(String.class).orElse("deepseek-chat");
    }
}
