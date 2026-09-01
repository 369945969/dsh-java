package com.deepseek.dsh.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.agent.TurnObserver;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.SessionCwd;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * Shared turn orchestration for Web and CLI modes.
 *
 * <p>Handles system-prompt injection, context messages, session-event logging,
 * and the ReAct agent loop with a {@link TurnObserver} that emits events
 * through a {@link SessionEventSink}. Web wraps the sink with WS broadcasting;
 * CLI wraps it with stdout printing.
 */
public final class TurnOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TurnOrchestrator.class);

    @FunctionalInterface
    public interface SessionEventSink {
        void emit(String sessionId, String eventType, Map<String, Object> data);
    }

    private final Context ctx;
    private final Agent agent;
    private final com.deepseek.dsh.web.server.WorkspaceRegistry workspaces;
    private final ConcurrentMap<String, Thread> runningTurns = new ConcurrentHashMap<>();

    public TurnOrchestrator(Context ctx, Agent agent,
                            com.deepseek.dsh.web.server.WorkspaceRegistry workspaces) {
        this.ctx = ctx;
        this.agent = agent;
        this.workspaces = workspaces;
    }

    public boolean isRunning(String sessionId) {
        return runningTurns.containsKey(sessionId);
    }

    public void cancel(String sessionId) {
        Thread t = runningTurns.remove(sessionId);
        if (t != null) t.interrupt();
    }

    public int nextTurn(String sessionId) {
        try {
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog slog = sessions.getOrCreate(SessionId.of(sessionId));
            int count = 0;
            // 从原始事件计数 user/message，跳过 source.kind=plugin 的上下文/技能注入消息
            // （deriveMessages 把注入消息也投影为 USER → 多算 → 轮次跳跃 0→3 而非 0→1）
            for (SessionEvent e : slog.events()) {
                if (!"user/message".equals(e.type())) continue;
                if (e.data() == null) continue;
                if (e.data().get("source") instanceof Map<?, ?> src && "plugin".equals(src.get("kind"))) continue;
                count++;
            }
            return count + 1;  // 1-indexed：第一轮 turn=1（前端 firstVisibleTurn 检查 turn > 0）
        } catch (Exception ignored) {
            return 0;
        }
    }

    public void prepareTurn(String sessionId, String text, int turn, String model,
                            String rpcId, SessionEventSink sink) {
        setupWorkspace(sessionId);
        if (turn <= 1) {  // 首轮注入 request/header + 上下文（兼容 0-indexed 旧会话 turn=0 与 1-indexed 新会话 turn=1）
            injectRequestHeader(sessionId, model, sink);
            injectContextMessages(sessionId, sink);
        }
        sink.emit(sessionId, "turn/start", Map.of("turn", turn));
        // source.rpcId 与前端 beginSubmission 的 requestId 对齐：前端据此把本地
        // 乐观 echo 与这条 durable user/message 配对 retire（observeSubmissionEvent
        // 要求 source.kind==='user' 且 source.rpcId 为字符串）。缺 rpcId 则 echo
        // 不被 retire，会与 durable 消息同时渲染，致"发送内容显示两次"。
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("kind", "user");
        if (rpcId != null && !rpcId.isBlank()) source.put("rpcId", rpcId);
        sink.emit(sessionId, "user/message", Map.of(
                "id", "u-" + UUID.randomUUID().toString().substring(0, 8),
                "content", List.of(Map.of("type", "text", "text", text)),
                "source", source));
    }

    public void runAgent(String sessionId, String text, int turn, String model,
                         SessionEventSink sink) {
        int[] step = {-1};
        setupWorkspace(sessionId);
        runningTurns.put(sessionId, Thread.currentThread());
        try {
            agent.runObserved(SessionId.of(sessionId), ScopeKey.random(), ctx, text,
                    buildObserver(sessionId, turn, model, step, sink));
        } catch (Exception e) {
            log.warn("agent turn {}: {}", Thread.currentThread().isInterrupted() ? "cancelled" : "failed", e.toString());
            handleTurnError(sessionId, turn, model, step, e, sink);
        } finally {
            runningTurns.remove(sessionId);
            SessionCwd.clear();
        }
        if (step[0] >= 0) sink.emit(sessionId, "step/end", Map.of("turn", turn, "step", step[0]));
        sink.emit(sessionId, "turn/end", Map.of("turn", turn,
                "reason", Map.of("kind", "complete")));
    }

    private void setupWorkspace(String sessionId) {
        if (workspaces == null) return;
        String wsPath = workspaces.findSessionWorkspacePath(sessionId);
        if (wsPath == null) return;
        Path wsDir = Path.of(wsPath);
        if (!Files.isDirectory(wsDir)) {
            String wsName = wsDir.getFileName() != null ? wsDir.getFileName().toString() : "workspace";
            wsDir = Path.of(System.getProperty("user.dir"), wsName);
            try { Files.createDirectories(wsDir); } catch (Exception e) { log.debug("create workspace dir failed: {}", e.toString()); }
        }
        SessionCwd.set(wsDir.toString());
    }

    private void injectRequestHeader(String sessionId, String model, SessionEventSink sink) {
        try {
            String sysPrompt = agent.composeSystemPrompt(ctx);
            // reason:'initial' 与 harness 对齐——inspectRequestPrompt 据 reason 判定首个 header 的
            // change（kind:'initial'），轨迹 request-header 节点据此渲染系统提示词（缺则不显示）。
            sink.emit(sessionId, "request/header", Map.of(
                    "header", Map.of(
                            "config", Map.of("provider", "openai-compatible", "model", model),
                            "system", sysPrompt),
                    "reason", "initial"));
            sink.emit(sessionId, "request/context", Map.of(
                    "provider", "openai-compatible", "model", model));
        } catch (Exception e) {
            log.debug("request/header injection failed: {}", e.toString());
        }
    }

    private void injectContextMessages(String sessionId, SessionEventSink sink) {
        String wsPath = workspaces != null ? workspaces.findSessionWorkspacePath(sessionId) : null;
        if (wsPath == null || wsPath.isEmpty()) wsPath = SessionCwd.get() != null ? SessionCwd.get() : System.getProperty("user.dir");

        injectAgentsMd(sessionId, wsPath, sink);
        injectRuntimeContext(sessionId, wsPath, sink);
        injectSkillList(sessionId, sink);
    }

    private void injectAgentsMd(String sessionId, String wsPath, SessionEventSink sink) {
        java.io.File agentsMd = new java.io.File(wsPath, "AGENTS.md");
        if (!agentsMd.isFile()) return;
        try {
            String content = Files.readString(agentsMd.toPath());
            if (content.length() > 8000) content = content.substring(0, 8000) + "\n…(truncated)";
            sink.emit(sessionId, "user/message", Map.of(
                    "id", "ctx-agents-" + UUID.randomUUID().toString().substring(0, 8),
                    "content", List.of(Map.of("type", "text",
                            "text", "<system-reminder>\nThe following workspace instructions may be relevant to your work. "
                                    + "Use them as guidance when applicable. More specific instructions take precedence over broader ones. "
                                    + "They do not override system, developer, or direct user instructions.\n\nInstructions from: AGENTS.md\n\n"
                                    + content)),
                    "source", Map.of("kind", "plugin", "plugin", "dsh-context", "form", "system-reminder"),
                    "role", "user"));
        } catch (Exception e) {
            log.debug("injectContextMessages AGENTS.md: {}", e.toString());
        }
    }

    private void injectRuntimeContext(String sessionId, String wsPath, SessionEventSink sink) {
        sink.emit(sessionId, "user/message", Map.of(
                "id", "ctx-runtime-" + UUID.randomUUID().toString().substring(0, 8),
                "content", List.of(Map.of("type", "text",
                        "text", "Current runtime context. This snapshot supersedes earlier runtime-context snapshots.\n\n"
                                + "Current DSH file policy: workspace-write. Any available operation enforced by the DSH file sandbox "
                                + "may modify files under the session workspace: \"" + wsPath + "\". "
                                + "Some platform temporary areas may also be writable.\n\n"
                                + "Approval policy: ask. Operations that require approval may ask through the configured answerers; "
                                + "without an available answerer, the request fails closed.")),
                "source", Map.of("kind", "plugin", "plugin", "dsh-system-prompt", "form", "snapshot"),
                "role", "user"));
    }

    private void injectSkillList(String sessionId, SessionEventSink sink) {
        StringBuilder skillList = new StringBuilder();
        skillList.append("<system-reminder>\nA skill is a reusable set of task-specific instructions. The following skills are available in this session:\n\n<available_skills>\n");
        try {
            var skills = ctx.get(com.deepseek.dsh.skill.SkillService.class).orElse(null);
            if (skills != null) {
                for (var s : skills.list(null)) {
                    skillList.append("- `").append(s.name()).append("`: ").append(s.description()).append('\n');
                }
            }
        } catch (Exception e) {
            log.debug("injectContextMessages skills: {}", e.toString());
        }
        skillList.append("</available_skills>\n</system-reminder>");
        sink.emit(sessionId, "user/message", Map.of(
                "id", "ctx-skills-" + UUID.randomUUID().toString().substring(0, 8),
                "content", List.of(Map.of("type", "text", "text", skillList.toString())),
                "source", Map.of("kind", "plugin", "plugin", "dsh-skill", "form", "system-reminder"),
                "role", "user"));
    }

    private TurnObserver buildObserver(String sessionId, int turn, String model,
                                       int[] step, SessionEventSink sink) {
        return new TurnObserver() {
            @Override public void onRequestHeader(String systemPrompt, String model) {}
            @Override public void onTurnStart(int t) {}
            @Override public void onUserMessage(String msg, String msgId) {}
            @Override public void onStepStart(int t, int s) {}
            @Override public void onStepEnd(int t, int s) {}
            @Override public void onTurnEnd(int t, String reason) {}
            @Override public void onToolDenied(String callId, String reason) {
                sink.emit(sessionId, "tool/result", Map.of(
                        "message", Map.of(
                                "source", Map.of("callId", callId),
                                "content", List.of(Map.of("type", "text", "text", "（已拒绝：" + reason + "）"))),
                        "turn", turn, "step", step[0]));
            }
            @Override public void onAssistantMessage(String content, String reasoning, String assistantMsgId) {
                if ((content == null || content.isEmpty()) && (reasoning == null || reasoning.isEmpty())) return;
                if (step[0] >= 0) sink.emit(sessionId, "step/end", Map.of("turn", turn, "step", step[0]));
                step[0]++;
                sink.emit(sessionId, "step/start", Map.of("turn", turn, "step", step[0]));
                if (reasoning != null && !reasoning.isEmpty()) {
                    sink.emit(sessionId, "assistant/chunk", Map.of(
                            "chunk", Map.of("type", "reasoning-delta", "index", 0, "text", reasoning),
                            "turn", turn, "step", step[0]));
                }
                if (content != null && !content.isEmpty()) {
                    sink.emit(sessionId, "assistant/chunk", Map.of(
                            "chunk", Map.of("type", "text-delta", "index", 0, "text", content),
                            "turn", turn, "step", step[0]));
                    sink.emit(sessionId, "assistant/message", Map.of(
                            "message", Map.of(
                                    "id", "a-" + UUID.randomUUID().toString().substring(0, 8),
                                    "content", List.of(Map.of("type", "text", "text", content)),
                                    "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", model)),
                            "turn", turn, "step", step[0]));
                }
            }
            @Override public void onToolCall(String callId, String name, String argumentsJson) {
                sink.emit(sessionId, "tool/call", Map.of(
                        "callId", callId, "name", name, "arguments", argumentsJson,
                        "turn", turn, "step", step[0]));
            }
            @Override public void onToolResult(String callId, String resultText) {
                Map<String, Object> toolResultBlock = new LinkedHashMap<>();
                toolResultBlock.put("type", "tool-result");
                toolResultBlock.put("toolCallId", callId);
                toolResultBlock.put("content", List.of(Map.of("type", "text", "text", resultText)));
                toolResultBlock.put("isError", false);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("content", List.of(toolResultBlock));
                msg.put("source", Map.of("callId", callId));
                sink.emit(sessionId, "tool/result", Map.of(
                        "message", msg,
                        "turn", turn, "step", step[0]));
            }
        };
    }

    private void handleTurnError(String sessionId, int turn, String model,
                                 int[] step, Exception e, SessionEventSink sink) {
        if (step[0] < 0) { step[0] = 0; sink.emit(sessionId, "step/start", Map.of("turn", turn, "step", step[0])); }
        String detail = e.getMessage();
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) detail += " — " + cause.getMessage();
        String errMsg = "模型调用失败：" + detail;
        sink.emit(sessionId, "assistant/chunk", Map.of(
                "chunk", Map.of("type", "text-delta", "index", 0, "text", errMsg),
                "turn", turn, "step", step[0]));
        sink.emit(sessionId, "assistant/message", Map.of(
                "message", Map.of(
                        "id", "a-err-" + UUID.randomUUID().toString().substring(0, 8),
                        "content", List.of(Map.of("type", "text", "text", errMsg)),
                        "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", model)),
                "turn", turn, "step", step[0]));
    }

    public static String generateTitle(SessionLog sl) {
        try {
            for (SessionEvent e : sl.events()) {
                if (!"user/message".equals(e.type())) continue;
                if (e.data() == null) continue;
                if (e.data().get("source") instanceof Map<?, ?> src && "plugin".equals(src.get("kind"))) continue;
                String t = extractUserText(e.data());
                if (t != null && !t.isBlank()) {
                    t = t.trim().replaceAll("\\s+", " ");
                    return t.length() > 40 ? t.substring(0, 40) + "…" : t;
                }
            }
        } catch (Exception ignored) {}
        return "新会话";
    }

    @SuppressWarnings("unchecked")
    private static String extractUserText(Map<String, Object> data) {
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

    public SessionLog forkSession(String parentSid, SessionEventSink sink) {
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog parent = sessions.getOrCreate(SessionId.of(parentSid));
        SessionLog child = sessions.create();
        for (SessionEvent e : parent.snapshot()) {
            sessions.persist(child.append(e.type(), e.data(), e.surfaceOp()));
        }
        String parentTitle = generateTitle(parent);
        sink.emit(child.sessionId().value(), "user/message", Map.of(
                "id", "ctx-fork-" + UUID.randomUUID().toString().substring(0, 8),
                "content", List.of(Map.of("type", "text",
                        "text", "<system-reminder>\nThis session was forked from session " + parentSid
                                + " (title: \"" + parentTitle + "\"). "
                                + "When asked about your origin, state that this session was forked from \"" + parentTitle
                                + "\" with session id " + parentSid + ".\n</system-reminder>")),
                "source", Map.of("kind", "plugin", "plugin", "dsh-fork", "form", "system-reminder"),
                "role", "user"));
        return child;
    }
}
