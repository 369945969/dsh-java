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
    private final ConcurrentMap<String, SessionId> sessions = new ConcurrentHashMap<>();

    public DshRpcServer(Context context, Agent agent) {
        this.context = context;
        this.agent = agent;
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
            r.put("model", System.getenv().getOrDefault("DSH_MODEL", "deepseek-chat"));
            r.put("cwd", System.getProperty("user.dir"));
            r.put("protocolVersion", "2025-01-stdio-jsonrpc");
            return r;
        });

        // 创建会话（Java 侧便利方法；TS 中会话由 session/prompt 隐式创建）
        dispatcher.register("session.create", (params, ctx) -> {
            String sid = params.path("sessionId").asText("");
            if (sid.isBlank()) sid = UUID.randomUUID().toString();
            sessions.put(sid, SessionId.of(sid));
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
            String reply = agent.run(sessionId, ScopeKey.random(), context, message);
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

        // session/fork —— fork 出保留父会话记忆的子会话（回放父事件）
        dispatcher.register("session/fork", (params, ctx) -> {
            String parentSid = params.path("sessionId").asText();
            SessionId parentId = sessions.get(parentSid);
            ObjectNode r = ctx.mapper().createObjectNode();
            if (parentId == null) {
                r.put("error", "Parent session not found: " + parentSid);
                return r;
            }
            Sessions svc = context.require(Sessions.class);
            SessionLog parent = svc.getOrCreate(parentId);
            SessionLog child = svc.create();
            int n = 0;
            for (SessionEvent e : parent.snapshot()) {
                child.append(e.type(), e.payload(), e.lineage());
                n++;
            }
            sessions.put(child.sessionId().value(), child.sessionId());
            r.put("childSessionId", child.sessionId().value());
            r.put("parentSessionId", parentSid);
            r.put("replayedEvents", n);
            return r;
        });

        // session/compact —— 对会话历史触发上下文压缩
        dispatcher.register("session/compact", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            int maxTokens = params.path("maxTokens").asInt(2048);
            SessionId sessionId = sessions.get(sid);
            ObjectNode r = ctx.mapper().createObjectNode();
            if (sessionId == null) {
                r.put("error", "Session not found: " + sid);
                return r;
            }
            Sessions svc = context.require(Sessions.class);
            SessionLog log = svc.getOrCreate(sessionId);
            List<ChatMessage> msgs = log.deriveMessages().messages();
            CompactionService comp = context.get(CompactionService.class).orElse(null);
            r.put("sessionId", sid);
            r.put("before", msgs.size());
            if (comp == null) {
                r.put("error", "compaction service not registered");
                return r;
            }
            List<ChatMessage> compacted = comp.compact(msgs, maxTokens);
            r.put("after", compacted.size());
            r.put("compacted", compacted.size() < msgs.size());
            return r;
        });

        // skill/list —— 列出已发现技能
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

        // skill/get —— 加载并渲染单个技能（<skill_content> 块）
        dispatcher.register("skill/get", (params, ctx) -> {
            String name = params.path("name").asText();
            SkillService skills = context.get(SkillService.class).orElse(null);
            ObjectNode r = ctx.mapper().createObjectNode();
            if (skills == null) {
                r.put("error", "skill service not registered");
                return r;
            }
            java.util.Optional<SkillDefinition> def = skills.get(name, null);
            if (def.isEmpty()) {
                r.put("found", false);
                r.put("name", name);
                return r;
            }
            r.put("found", true);
            r.put("name", name);
            r.put("rendered", SkillRenderer.render(def.get()));
            return r;
        });

        // subagent/task —— 委派子任务给子 agent（多 agent 编排：父子委派）
        dispatcher.register("subagent/task", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            String task = params.path("task").asText();
            SessionId sessionId = sessions.computeIfAbsent(sid, SessionId::of);
            SubagentService sub = context.get(SubagentService.class).orElse(null);
            ObjectNode r = ctx.mapper().createObjectNode();
            if (sub == null) {
                r.put("error", "subagent service not registered");
                return r;
            }
            DelegationResult res = sub.delegate(sessionId, ScopeKey.random(), context, agent, task);
            r.put("report", res.report());
            r.put("success", res.success());
            if (res.childSessionId() != null) r.put("childSessionId", res.childSessionId());
            r.put("forwardedEventCount", res.forwardedEventCount());
            return r;
        });

        // team/run —— 多 agent 并行编排（临时团队，主 agent 扮演两名成员）
        dispatcher.register("team/run", (params, ctx) -> {
            String task = params.path("task").asText();
            ObjectNode r = ctx.mapper().createObjectNode();
            DefaultTeamsProvider teams = new DefaultTeamsProvider();
            teams.setContext(context);
            teams.registerMember("reviewer", agent);
            teams.registerMember("tester", agent);
            var res = teams.runTeamTask(task);
            r.put("summary", res.summary());
            r.put("memberCount", res.reports().size());
            r.put("allSucceeded", res.allSucceeded());
            return r;
        });

        // shutdown —— 对齐 TS SDK 协议
        dispatcher.register("shutdown", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("status", "ok");
            return r;
        });
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
