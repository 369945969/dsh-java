package com.deepseek.dsh.sdk.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.deepseek.dsh.sdk.protocol.JsonRpcClient;

/**
 * DSH 高层 JSON-RPC 客户端 —— 对应原 Harness 的 {@code sdk/client}。
 *
 * <p>在底层 {@link JsonRpcClient}（行分隔 JSON-RPC 传输）之上封装类型化 API，
 * 覆盖后端 RPC 全部功能：{@code initialize} / {@code session.create} /
 * {@code session.list} / {@code session/prompt} / {@code session.history} /
 * {@code session.delete} / {@code shutdown} / {@code health}。
 *
 * <p>两种构造模式：
 * <ul>
 *   <li><b>子进程</b>：{@link #HarnessClient(String)} 启动外部运行时命令，
 *       通过其 stdio 通信（对应 TS 的进程外运行时 SDK）。</li>
 *   <li><b>流式（进程内）</b>：{@link #HarnessClient(InputStream, OutputStream)}
 *       在给定流上通信，供测试在同进程内驱动 RPC 服务端。</li>
 * </ul>
 *
 * <p>设计模式：远程代理（Remote Proxy）+ 外观（封装线协议为类型化 API）。
 */
public final class HarnessClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessClient.class);

    private final JsonRpcClient transport;
    /** 子进程模式时持有，便于关闭；流式模式为 null。 */
    private final Process process;

    /** 子进程模式：启动运行时命令并接管其 stdio。 */
    public HarnessClient(String runtimeCommand) throws Exception {
        String[] cmd = runtimeCommand.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.transport = new JsonRpcClient(process.getInputStream(), process.getOutputStream());
    }

    /** 流式模式：在给定流上通信（不持有进程）。 */
    public HarnessClient(InputStream in, OutputStream out) {
        this.process = null;
        this.transport = new JsonRpcClient(in, out);
    }

    // ---- 协议：initialize / shutdown / health ----

    /** initialize —— 返回 provider/model/cwd/protocolVersion。 */
    public CompletableFuture<InitializeResult> initialize() {
        return transport.request("initialize", null).thenApply(HarnessClient::toInitialize);
    }

    /** shutdown —— 优雅关闭。 */
    public CompletableFuture<Void> shutdown() {
        return transport.request("shutdown", null).thenApply(n -> null);
    }

    /** health —— 健康检查。 */
    public CompletableFuture<HealthResult> health() {
        return transport.request("health", null).thenApply(HarnessClient::toHealth);
    }

    // ---- 会话：create / list / prompt / history / delete ----

    /** session.create —— 创建会话；sid 省略则由服务端生成。 */
    public CompletableFuture<String> createSession() {
        return transport.request("session.create", java.util.Map.of())
                .thenApply(n -> n.path("sessionId").asText());
    }

    /** session.create —— 指定 sid 创建。 */
    public CompletableFuture<String> createSession(String sessionId) {
        return transport.request("session.create", java.util.Map.of("sessionId", sessionId))
                .thenApply(n -> n.path("sessionId").asText());
    }

    /** session.list —— 列出全部会话 ID。 */
    public CompletableFuture<SessionListResult> listSessions() {
        return transport.request("session.list", java.util.Map.of())
                .thenApply(HarnessClient::toSessionList);
    }

    /** session/prompt —— 运行一轮对话，返回回复与状态（对齐 TS SDK 协议）。 */
    public CompletableFuture<PromptResult> prompt(String sessionId, String message) {
        return transport.request("session/prompt",
                java.util.Map.of("sessionId", sessionId, "message", message))
                .thenApply(HarnessClient::toPrompt);
    }

    /** session.history —— 查询会话历史投影。 */
    public CompletableFuture<HistoryResult> history(String sessionId) {
        return transport.request("session.history",
                java.util.Map.of("sessionId", sessionId))
                .thenApply(HarnessClient::toHistory);
    }

    /** session.delete —— 删除会话。 */
    public CompletableFuture<Boolean> deleteSession(String sessionId) {
        return transport.request("session.delete",
                java.util.Map.of("sessionId", sessionId))
                .thenApply(n -> n.path("deleted").asBoolean(false));
    }

    /** session/fork —— fork 出保留父会话记忆的子会话。 */
    public CompletableFuture<ForkResult> forkSession(String parentSessionId) {
        return transport.request("session/fork",
                java.util.Map.of("sessionId", parentSessionId))
                .thenApply(HarnessClient::toFork);
    }

    /** session/compact —— 对会话历史触发上下文压缩。 */
    public CompletableFuture<CompactResult> compactSession(String sessionId, int maxTokens) {
        return transport.request("session/compact",
                java.util.Map.of("sessionId", sessionId, "maxTokens", maxTokens))
                .thenApply(HarnessClient::toCompact);
    }

    /** skill/list —— 列出已发现技能。 */
    public CompletableFuture<SkillListResult> skillList() {
        return transport.request("skill/list", java.util.Map.of())
                .thenApply(HarnessClient::toSkillList);
    }

    /** skill/get —— 加载并渲染单个技能。 */
    public CompletableFuture<SkillGetResult> skillGet(String name) {
        return transport.request("skill/get", java.util.Map.of("name", name))
                .thenApply(HarnessClient::toSkillGet);
    }

    /** subagent/task —— 委派子任务给子 agent。 */
    public CompletableFuture<SubagentTaskResult> subagentTask(String sessionId, String task) {
        return transport.request("subagent/task",
                java.util.Map.of("sessionId", sessionId, "task", task))
                .thenApply(HarnessClient::toSubagentTask);
    }

    /** team/run —— 多 agent 并行编排。 */
    public CompletableFuture<TeamRunResult> teamRun(String task) {
        return transport.request("team/run", java.util.Map.of("task", task))
                .thenApply(HarnessClient::toTeamRun);
    }

    /** 底层传输（供高级用法直接调用）。 */
    public JsonRpcClient transport() {
        return transport;
    }

    @Override
    public void close() {
        transport.close();
        if (process != null) process.destroyForcibly();
    }

    // ---- 结果解析（封装线协议为类型化记录） ----

    private static InitializeResult toInitialize(JsonNode n) {
        return new InitializeResult(
                n.path("provider").asText(""),
                n.path("model").asText(""),
                n.path("cwd").asText(""),
                n.path("protocolVersion").asText(""));
    }

    private static HealthResult toHealth(JsonNode n) {
        return new HealthResult(n.path("status").asText(""), n.path("agent").asText(""));
    }

    private static SessionListResult toSessionList(JsonNode n) {
        List<String> ids = new java.util.ArrayList<>();
        JsonNode arr = n.path("sessionIds");
        if (arr.isArray()) {
            for (JsonNode e : arr) ids.add(e.asText());
        }
        return new SessionListResult(ids, n.path("count").asInt(ids.size()));
    }

    private static PromptResult toPrompt(JsonNode n) {
        return new PromptResult(
                n.path("sessionId").asText(""),
                n.path("reply").asText(""),
                n.path("status").asText(""),
                n.path("totalTokens").asLong(0));
    }

    private static HistoryResult toHistory(JsonNode n) {
        List<JsonNode> messages = new java.util.ArrayList<>();
        JsonNode arr = n.path("messages");
        if (arr.isArray()) {
            for (JsonNode m : arr) messages.add(m);
        }
        String error = n.has("error") ? n.path("error").asText(null) : null;
        return new HistoryResult(messages, error);
    }

    private static ForkResult toFork(JsonNode n) {
        String err = n.has("error") ? n.path("error").asText(null) : null;
        return new ForkResult(
                n.path("childSessionId").asText(""),
                n.path("parentSessionId").asText(""),
                n.path("replayedEvents").asInt(0),
                err);
    }

    private static CompactResult toCompact(JsonNode n) {
        String err = n.has("error") ? n.path("error").asText(null) : null;
        return new CompactResult(
                n.path("sessionId").asText(""),
                n.path("before").asInt(0),
                n.path("after").asInt(0),
                n.path("compacted").asBoolean(false),
                err);
    }

    private static SkillListResult toSkillList(JsonNode n) {
        List<SkillSummary> skills = new java.util.ArrayList<>();
        JsonNode arr = n.path("skills");
        if (arr.isArray()) {
            for (JsonNode s : arr) {
                skills.add(new SkillSummary(
                        s.path("name").asText(""),
                        s.path("description").asText(""),
                        s.path("source").asText(""),
                        s.path("provider").asText("")));
            }
        }
        String err = n.has("error") ? n.path("error").asText(null) : null;
        return new SkillListResult(skills, n.path("count").asInt(skills.size()), err);
    }

    private static SkillGetResult toSkillGet(JsonNode n) {
        return new SkillGetResult(
                n.path("found").asBoolean(false),
                n.path("name").asText(""),
                n.path("rendered").asText(""),
                n.has("error") ? n.path("error").asText(null) : null);
    }

    private static SubagentTaskResult toSubagentTask(JsonNode n) {
        String err = n.has("error") ? n.path("error").asText(null) : null;
        return new SubagentTaskResult(
                n.path("report").asText(""),
                n.path("success").asBoolean(false),
                err);
    }

    private static TeamRunResult toTeamRun(JsonNode n) {
        String err = n.has("error") ? n.path("error").asText(null) : null;
        return new TeamRunResult(
                n.path("summary").asText(""),
                n.path("memberCount").asInt(0),
                n.path("allSucceeded").asBoolean(false),
                err);
    }

    // ---- 类型化结果记录 ----

    /** initialize 结果。 */
    public record InitializeResult(String provider, String model, String cwd, String protocolVersion) {}

    /** health 结果。 */
    public record HealthResult(String status, String agent) {}

    /** session.list 结果。 */
    public record SessionListResult(List<String> sessionIds, int count) {}

    /** session/prompt 结果。 */
    public record PromptResult(String sessionId, String reply, String status, long totalTokens) {}

    /** session.history 结果。 */
    public record HistoryResult(List<JsonNode> messages, String error) {}

    /** session/fork 结果。 */
    public record ForkResult(String childSessionId, String parentSessionId, int replayedEvents, String error) {}

    /** session/compact 结果。 */
    public record CompactResult(String sessionId, int before, int after, boolean compacted, String error) {}

    /** 技能摘要。 */
    public record SkillSummary(String name, String description, String source, String provider) {}

    /** skill/list 结果。 */
    public record SkillListResult(List<SkillSummary> skills, int count, String error) {}

    /** skill/get 结果。 */
    public record SkillGetResult(boolean found, String name, String rendered, String error) {}

    /** subagent/task 结果。 */
    public record SubagentTaskResult(String report, boolean success, String error) {}

    /** team/run 结果。 */
    public record TeamRunResult(String summary, int memberCount, boolean allSucceeded, String error) {}
}
