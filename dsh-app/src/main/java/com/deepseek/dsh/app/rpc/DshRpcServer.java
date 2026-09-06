package com.deepseek.dsh.app.rpc;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.app.bundle.BaseBundle;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.util.PluginRunner;
import com.deepseek.dsh.llm.config.ModelConfig;
import com.deepseek.dsh.llm.meter.TokenMeterService;
import com.deepseek.dsh.sdk.protocol.JsonRpcDispatcher;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentService;
import com.deepseek.dsh.compaction.CompactionService;
import com.deepseek.dsh.skill.SkillDefinition;
import com.deepseek.dsh.skill.SkillRenderer;
import com.deepseek.dsh.skill.SkillService;
import com.deepseek.dsh.teams.DefaultTeamsProvider;
import com.deepseek.dsh.web.TurnOrchestrator;

/**
 * RPC 服务端入口 —— 对应原 Harness 的 {@code dsh-jsonrpc-agent}（stdio 运行时）。
 *
 * <p>以 newline-delimited JSON-RPC 2.0 over stdio 暴露 agent 能力：会话的
 * 创建/列出/运行/删除、健康检查、关闭。后端装配复用 {@link BaseBundle}，
 * 协议读写复用 {@link JsonRpcDispatcher}，与 {@code AcpServer} / {@code JsonRpcAgentServer}
 * 同一基座（命令注册 + 前端控制器）。
 *
 * <p>启动：{@code java com.deepseek.dsh.app.rpc.DshRpcServer}；环境变量
 * {@code DEEPSEEK_API_KEY} / {@code DSH_BASE_URL} / {@code DSH_MODEL} 配置模型
 * （OpenAI 兼容端点，如阿里云 DashScope 的 glm-5.2）。
 *
 * <p>设计模式：命令注册 + 前端控制器 + 依赖注入（手动装配插件树）。
 */
public final class DshRpcServer {

    private static final Logger log = LoggerFactory.getLogger(DshRpcServer.class);

    private final JsonRpcDispatcher dispatcher = new JsonRpcDispatcher();
    private final Context context;
    private final Agent agent;
    private final TurnOrchestrator orchestrator;
    /** 与 Web apiproxy 共用的能力门面（compact/delete/skill.get/subagent/team 单点实现）。 */
    private final com.deepseek.dsh.web.api.AgentApiFacade apiFacade;
    private final ConcurrentMap<String, SessionId> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionCwds = new ConcurrentHashMap<>();

    public DshRpcServer(Context context, Agent agent) {
        this.context = context;
        this.agent = agent;
        this.orchestrator = new TurnOrchestrator(context, agent, null);
        this.apiFacade = new com.deepseek.dsh.web.api.AgentApiFacade(context, agent);
        registerMethods();
    }

    private void registerMethods() {
        // 健康检查（Java 侧便利方法）
        dispatcher.register("health", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("status", "ok");
            r.put("agent", agent.name());
            return r;
        });

        // initialize —— 对齐 TS SDK 协议：返回 provider/model/cwd
        dispatcher.register("initialize", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("provider", "deepseek-official");
            // 上报运行时实际模型（由 ModelProfileStore 同步 dataDir/model-config.json 的活跃档案），
            // 而非启动时的环境变量初值——否则网页配置的 glm-5.2 等自定义模型不会反映到 initialize。
            String runtimeModel = context.get(ModelConfig.class)
                    .map(ModelConfig::model)
                    .filter(m -> m != null && !m.isBlank())
                    .orElseGet(() -> System.getenv().getOrDefault("DSH_MODEL", "deepseek-chat"));
            r.put("model", runtimeModel);
            r.put("cwd", System.getProperty("user.dir"));
            r.put("protocolVersion", "2025-01-stdio-jsonrpc");
            r.put("version", "0.1.2-alpha.1");
            return r;
        });

        // 创建会话（Java 侧便利方法；TS 中会话由 session/prompt 隐式创建）
        dispatcher.register("session.create", (params, ctx) -> {
            String sid = params.path("sessionId").asText("");
            if (sid.isBlank()) sid = UUID.randomUUID().toString();
            sessions.put(sid, SessionId.of(sid));
            String cwd = params.path("cwd").asText("");
            if (!cwd.isBlank()) sessionCwds.put(sid, cwd);
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("sessionId", sid);
            return r;
        });

        // 列出会话
        dispatcher.register("session.list", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.putPOJO("sessionIds", sessions.keySet());
            r.put("count", sessions.size());
            return r;
        });

        // session/prompt —— 对齐 TS SDK 协议：运行一轮对话
        dispatcher.register("session/prompt", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            String message = params.path("message").asText();
            SessionId sessionId = sessions.computeIfAbsent(sid, SessionId::of);
            // set session cwd so the agent's system prompt uses the workspace path
            String cwd = sessionCwds.get(sid);
            if (cwd != null) com.deepseek.dsh.core.context.SessionCwd.set(cwd);
            String model = context.get(ModelConfig.class).map(ModelConfig::model).orElse("deepseek-chat");
            Sessions svc = context.require(Sessions.class);
            var sink = new RpcEventSink(svc);
            int turn = orchestrator.nextTurn(sid);
            orchestrator.prepareTurn(sid, message, turn, model, null, sink);
            orchestrator.runAgent(sid, message, turn, model, sink);
            String reply = "";
            SessionLog slog = svc.getOrCreate(sessionId);
            var msgs = slog.deriveMessages().messages();
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if (msgs.get(i).role() == ChatMessage.Role.ASSISTANT) {
                    reply = msgs.get(i).content() != null ? msgs.get(i).content() : "";
                    break;
                }
            }
            long totalTokens = context.get(TokenMeterService.class)
                    .map(TokenMeterService::totalTokens).orElse(0L);
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("sessionId", sid);
            r.put("reply", reply);
            r.put("status", "ok");
            r.put("totalTokens", totalTokens);
            return r;
        });

        // 删除会话
        dispatcher.register("session.delete", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            SessionId removed = sessions.remove(sid);
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("sessionId", sid);
            r.put("deleted", removed != null);
            return r;
        });

        // 查询会话历史投影
        dispatcher.register("session.history", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            SessionId sessionId = sessions.get(sid);
            ObjectNode r = ctx.mapper().createObjectNode();
            if (sessionId != null) {
                Sessions sessions = context.require(Sessions.class);
                SessionLog log = sessions.getOrCreate(sessionId);
                r.putPOJO("messages", log.deriveMessages().messages());
            } else {
                r.put("error", "Session not found: " + sid);
            }
            return r;
        });

        // session.page —— 0.1.2 别名，返回 records（打包格式）+ hasMore
        dispatcher.register("session.page", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            if (sid.isEmpty()) sid = params.path("address").path("sessionId").asText("");
            SessionId sessionId = sessions.getOrDefault(sid, SessionId.of(sid));
            ObjectNode r = ctx.mapper().createObjectNode();
            Sessions sessions = context.require(Sessions.class);
            SessionLog log = sessions.getOrCreate(sessionId);
            r.putPOJO("records", log.events());
            r.put("hasMore", false);
            return r;
        });

        // session/fork —— fork 出保留父会话记忆的子会话（回放父事件 + 注入 forked-from）
        dispatcher.register("session/fork", (params, ctx) -> {
            String parentSid = params.path("sessionId").asText();
            ObjectNode r = ctx.mapper().createObjectNode();
            Sessions svc = context.require(Sessions.class);
            var sink = new com.deepseek.dsh.app.rpc.RpcEventSink(svc);
            SessionLog child = orchestrator.forkSession(parentSid, sink);
            sessions.put(child.sessionId().value(), child.sessionId());
            String parentCwd = sessionCwds.get(parentSid);
            if (parentCwd != null) sessionCwds.put(child.sessionId().value(), parentCwd);
            r.put("childSessionId", child.sessionId().value());
            r.put("parentSessionId", parentSid);
            r.put("replayedEvents", child.size());
            return r;
        });

        // session/compact —— 委托共享 facade（compact/delete/skill.get/subagent/team 单点实现）
        dispatcher.register("session/compact", (params, ctx) ->
                toNode(ctx.mapper(), apiFacade.sessionCompact(
                        params.path("sessionId").asText(),
                        params.path("maxTokens").asInt(2048))));

        // skill/list —— 列出已发现技能（字段契约与 web apiproxy 不同：含 source/provider，
        // 不做 userInvocable 过滤；保留各自实现，facade 只托管契约一致的 compact/delete/
        // skill.get/subagent/team/shutdown）
        dispatcher.register("skill/list", (params, ctx) -> {
            SkillService skills = context.get(SkillService.class).orElse(null);
            ObjectNode r = ctx.mapper().createObjectNode();
            if (skills == null) {
                r.put("error", "skill service not registered");
                return r;
            }
            var arr = r.putArray("skills");
            for (var s : skills.list(null)) {
                var o = arr.addObject();
                o.put("name", s.name());
                o.put("description", s.description());
                o.put("source", s.source());
                o.put("provider", s.provider());
            }
            r.put("count", skills.list(null).size());
            return r;
        });

        // skill/get —— 委托共享 facade
        dispatcher.register("skill/get", (params, ctx) ->
                toNode(ctx.mapper(), apiFacade.skillGet(params.path("name").asText())));

        // subagent/task —— 委派子任务给子 agent（多 agent 编排：父子委派）
        dispatcher.register("subagent/task", (params, ctx) ->
                toNode(ctx.mapper(), apiFacade.subagentTask(
                        params.path("sessionId").asText(),
                        params.path("task").asText())));

        // team/run —— 多 agent 并行编排（临时团队，主 agent 扮演两名成员）
        dispatcher.register("team/run", (params, ctx) ->
                toNode(ctx.mapper(), apiFacade.teamRun(params.path("task").asText())));

        // shutdown —— 对齐 TS SDK 协议
        dispatcher.register("shutdown", (params, ctx) ->
                toNode(ctx.mapper(), apiFacade.shutdown()));
    }

    /** facade 返回 Map → RPC 的 ObjectNode（单点转换，供所有委托 handler 复用）。 */
    private static ObjectNode toNode(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                     java.util.Map<String, Object> map) {
        return mapper.valueToTree(map).isObject()
                ? (ObjectNode) mapper.valueToTree(map)
                : mapper.createObjectNode();
    }

    /** 在 stdio 上运行 newline-delimited JSON-RPC 循环。 */
    public void runLoop() throws java.io.IOException {
        dispatcher.runLoop(System.in, System.out);
    }

    /** 在给定流上运行 JSON-RPC 循环（供进程内测试与自定义传输）。 */
    public void runLoop(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        dispatcher.runLoop(in, out);
    }

    /** 入口：装配插件树并启动 RPC 循环。 */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("DSH_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("DSH_MODEL", "deepseek-chat");
        Path dataDir = Path.of(System.getenv().getOrDefault("DSH_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".dsh").toString()));

        log.info("Starting RPC server: model={}, baseUrl={}", model, baseUrl);
        Context context = Context.root();
        PluginRunner runner = new PluginRunner();
        Agent agent = new BaseBundle(apiKey, baseUrl, model, dataDir).assemble(context, runner);

        DshRpcServer server = new DshRpcServer(context, agent);
        // 收到 shutdown 后优雅退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Unloading plugin tree...");
            runner.stop();
            context.dispose();
        }));
        server.runLoop();
        log.info("RPC loop ended, exiting");
        runner.stop();
        context.dispose();
    }
}
