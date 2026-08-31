package com.deepseek.dsh.agent.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.agent.TurnObserver;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.adapter.LlmChunk;
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

/**
 * ReAct Agent Loop —— 模型→工具→模型循环，直到模型停止或达上限。
 *
 * <p>所有事件通过 {@link TurnObserver} 回调上报，由上层负责持久化和广播。
 * 本类不再直接 append SessionLog —— turn/start、user/message、assistant/message、
 * tool/call、tool/result 等事件由观察者（ApiproxyController.sendSessionEvent）处理。
 */
public class ReActAgentLoop implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentLoop.class);
    private final LlmModel model;
    private final ToolPipeline pipeline;
    private final com.deepseek.dsh.tools.registry.ToolRegistry toolRegistry;
    private final ThreadLocal<TurnObserver> observer = new ThreadLocal<>();
    private volatile String systemPrompt = "You are a coding agent powered by the glm-5.2 model.";

    public ReActAgentLoop(LlmModel model, ToolPipeline pipeline, com.deepseek.dsh.tools.registry.ToolRegistry toolRegistry) {
        this.model = model;
        this.pipeline = pipeline;
        this.toolRegistry = toolRegistry;
    }

    @Override public String name() { return "DeepSeek-Harness"; }
    @Override public String systemPrompt() { return systemPrompt; }
    @Override public void setSystemPrompt(String sp) { this.systemPrompt = sp; }
    @Override public String composeSystemPrompt(Context ctx) { return composedSystemPrompt(ctx); }

    @Override
    public String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) throws Exception {
        return runObserved(sessionId, scopeKey, ctx, userMessage, null);
    }

    @Override
    public String runObserved(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                               String userMessage, TurnObserver obs) throws Exception {
        observer.set(obs);
        try {
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog sessionLog = sessions.getOrCreate(sessionId);
            int turn = countUserMessages(sessionLog);

            if (obs != null) {
                obs.onTurnStart(turn);
                obs.onUserMessage(userMessage, "u-" + UUID.randomUUID().toString().substring(0, 8));
            }

            int maxSteps = LoopHierarchy.DEFAULT_MAX_STEPS;
            String lastContent = "";
            for (int stepNo = 0; stepNo < maxSteps; stepNo++) {
                StepResult step = runStep(sessionId, scopeKey, ctx, sessionLog, turn, stepNo);
                ctx.get(TokenMeterService.class).ifPresent(m -> m.record(step.response()));

                ContinueDecision decision = decideContinue(step.response());
                lastContent = step.response().content();

                if (decision != ContinueDecision.CONTINUE) {
                    if (obs != null) obs.onTurnEnd(turn, "stop".equalsIgnoreCase(step.response().finishReason()) ? "complete" : step.response().finishReason());
                    return lastContent;
                }
            }
            log.warn("Reached max steps {}, terminating turn", maxSteps);
            if (obs != null) obs.onTurnEnd(turn, "max-steps");
            return lastContent;
        } finally {
            observer.remove();
        }
    }

    private int countUserMessages(SessionLog sessionLog) {
        int count = 0;
        for (var e : sessionLog.snapshot()) {
            if ("user/message".equals(e.type())) count++;
        }
        return count;
    }

    protected StepResult runStep(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                                 SessionLog sessionLog, int turn, int stepNo) throws Exception {
        TurnObserver o = observer.get();

        SessionEvent.Projection projection = sessionLog.deriveMessages();
        List<ChatMessage> messages = new ArrayList<>(projection.messages());
        String sysPrompt = composedSystemPrompt(ctx);
        if (messages.isEmpty() || messages.get(0).role() != ChatMessage.Role.SYSTEM) {
            messages.add(0, ChatMessage.system(sysPrompt));
        }

        LlmRequest request = LlmRequest.of(messages, toolRegistry.schemas(), modelIdentifier(ctx));

        if (o != null) o.onStepStart(turn, stepNo);

        LlmResponse response = model.streamCollect(request, chunk -> {
            if (o != null) o.onAssistantChunk(chunk.delta(), chunk.reasoningDelta());
        });

        String assistantMsgId = "a-" + UUID.randomUUID().toString().substring(0, 8);
        if (o != null) o.onAssistantMessage(response.content(), response.reasoning(), assistantMsgId);

        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            dispatchToolCalls(sessionId, scopeKey, ctx, response.toolCalls(), o);
        }

        if (o != null) o.onStepEnd(turn, stepNo);
        return new StepResult(response, messages);
    }

    /**
     * 派发本 step 的工具调用：按 agent-loop.maxParallelToolCalls 限流并行（默认 1=串行）。
     * observer 由主线程捕获并显式传入（ThreadLocal 不会传播到工作线程，否则事件丢失）。
     * SessionLog.append 为 synchronized + AtomicLong seq，并发追加安全；事件按 callId 关联。
     */
    protected void dispatchToolCalls(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                                     List<ChatMessage.ToolCall> calls, TurnObserver o) throws Exception {
        int cap = maxParallelToolCalls(ctx);
        if (cap <= 1 || calls.size() <= 1) {
            for (ChatMessage.ToolCall tc : calls) executeToolCall(sessionId, scopeKey, ctx, tc, o);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(cap, calls.size()));
        try {
            CompletableFuture<?>[] futs = calls.stream()
                    .map(tc -> CompletableFuture.runAsync(() -> {
                        try { executeToolCall(sessionId, scopeKey, ctx, tc, o); }
                        catch (Exception e) { throw new CompletionException(e); }
                    }, pool))
                    .toArray(CompletableFuture[]::new);
            try { CompletableFuture.allOf(futs).join(); }
            catch (CompletionException ce) {
                Throwable c = ce.getCause() instanceof CompletionException ? ce.getCause().getCause() : ce.getCause();
                if (c instanceof Exception) throw (Exception) c;
                throw ce;
            }
        } finally { pool.shutdownNow(); }
    }

    private int maxParallelToolCalls(Context ctx) {
        return ctx.get(com.deepseek.dsh.settings.SettingsService.class)
                .map(s -> s.getAll("agent-loop").get("maxParallelToolCalls"))
                .map(v -> { try { int n = (int) Double.parseDouble(v); return n < 1 ? 10 : n; } catch (Exception e) { return 10; } })
                .orElse(10);
    }

    protected void executeToolCall(SessionId sessionId, ScopeKey scopeKey, Context ctx,
                                    ChatMessage.ToolCall tc, TurnObserver o) throws Exception {
        String callId = (tc.id() == null || tc.id().isEmpty()) ? "call-" + UUID.randomUUID().toString().substring(0, 12) : tc.id();
        var perm = ctx.get(com.deepseek.dsh.interaction.permission.PermissionService.class).orElse(null);
        if (perm != null) {
            var d = perm.check(tc.name(), scopeKey.value().toString());
            if (d == com.deepseek.dsh.interaction.permission.PermissionPreset.Decision.DENY) {
                if (o != null) o.onToolDenied(callId, "权限不足");
                return;
            }
            if (d == com.deepseek.dsh.interaction.permission.PermissionPreset.Decision.ASK) {
                var approval = ctx.get(com.deepseek.dsh.interaction.approval.ApprovalService.class).orElse(null);
                if (approval != null) {
                    var ar = approval.request(
                            com.deepseek.dsh.interaction.approval.ApprovalRequest.of(
                                    "工具调用: " + tc.name(), "参数: " + tc.argumentsJson())).get();
                    if (!ar.approved()) {
                        if (o != null) o.onToolDenied(callId, "用户拒绝: " + ar.feedback());
                        return;
                    }
                }
            }
        }

        if (o != null) o.onToolCall(callId, tc.name(), tc.argumentsJson());

        var toolCtx = new ToolContext(sessionId, scopeKey, ctx);
        var result = pipeline.execute(
                new ToolExecutionRequest(tc.name(), callId, parseArgs(tc.argumentsJson()), toolCtx));

        if (o != null) o.onToolResult(callId, result.text());
    }

    protected ContinueDecision decideContinue(LlmResponse response) {
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) return ContinueDecision.CONTINUE;
        if ("stop".equalsIgnoreCase(response.finishReason()) || response.finishReason() == null) return ContinueDecision.STOP;
        return ContinueDecision.CONTINUE;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private String modelIdentifier(Context ctx) {
        return ctx.get(String.class).orElse("deepseek-chat");
    }

    private String composedSystemPrompt(Context ctx) {
        String prompt = systemPrompt;
        String cwd = com.deepseek.dsh.core.context.SessionCwd.get();
        if (cwd == null || cwd.isEmpty()) cwd = System.getProperty("user.dir");
        String model = ctx.get(String.class).orElse("deepseek-chat");
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        String shell = osName.toLowerCase().contains("win") ? "powershell" : "bash";
        String platform = osName + " (" + osArch + "), shell: " + shell;
        prompt = prompt.replace("{{cwd}}", cwd);
        prompt = prompt.replace("{{model}}", model);
        prompt = prompt.replace("{{platform}}", platform);
        return prompt;
    }

    public record StepResult(LlmResponse response, List<ChatMessage> messages) {}

    public enum ContinueDecision { CONTINUE, STOP }

    public static class LoopHierarchy {
        public static final int DEFAULT_MAX_STEPS = 50;
    }
}
