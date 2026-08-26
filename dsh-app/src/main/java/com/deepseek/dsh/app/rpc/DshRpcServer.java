package com.deepseek.dsh.app.rpc;

import java.nio.file.Path;
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
import com.deepseek.dsh.session.log.SessionLog;

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
                r.put("error", "会话不存在: " + sid);
            }
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
        Path dataDir = Path.of(System.getProperty("user.home"), ".dsh");

        log.info("启动 RPC 服务端: model={}, baseUrl={}", model, baseUrl);
        Context context = Context.root();
        PluginRunner runner = new PluginRunner();
        Agent agent = new BaseBundle(apiKey, baseUrl, model, dataDir).assemble(context, runner);

        DshRpcServer server = new DshRpcServer(context, agent);
        // 收到 shutdown 后优雅退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("卸载插件树...");
            runner.stop();
            context.dispose();
        }));
        server.runLoop();
        log.info("RPC 循环结束，退出");
        runner.stop();
        context.dispose();
    }
}
