package com.deepseek.dsh.acp;

import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.config.ModelConfig;
import com.deepseek.dsh.sdk.protocol.JsonRpcDispatcher;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.web.TurnOrchestrator;

/**
 * ACP 服务器（Automation-only Agent Client Protocol）—— 对应原 Harness 的 {@code acp}。
 *
 * <p><b>重构后</b>：JSON-RPC 读写/解析/循环逻辑全部委托给共用的 {@link JsonRpcDispatcher}，
 * 本类仅注册方法处理器（命令模式），消除此前与 JsonRpcAgentServer 逐字节重复的样板。
 *
 * <p>设计模式：命令（每个 method 注册一个 handler）+ 前端控制器（dispatcher 统一分发）。
 */
public final class AcpServer {

    private static final Logger log = LoggerFactory.getLogger("dsh.acp.events");

    private final JsonRpcDispatcher dispatcher = new JsonRpcDispatcher();
    private final Context context;
    private final Agent agent;
    private final TurnOrchestrator orchestrator;
    private final ConcurrentMap<String, SessionId> sessions = new ConcurrentHashMap<>();

    public AcpServer(Context context, Agent agent) {
        this.context = context;
        this.agent = agent;
        this.orchestrator = new TurnOrchestrator(context, agent, null);
        registerMethods();
    }

    private void registerMethods() {
        dispatcher.register("session.create", (params, ctx) -> {
            String sid = UUID.randomUUID().toString();
            sessions.put(sid, SessionId.of(sid));
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("sessionId", sid);
            return r;
        });

        dispatcher.register("session.run", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            String message = params.path("message").asText();
            SessionId sessionId = sessions.computeIfAbsent(sid, SessionId::of);
            String model = context.get(ModelConfig.class).map(ModelConfig::model).orElse("deepseek-chat");
            Sessions svc = context.require(Sessions.class);
            var sink = new AcpEventSink(svc);
            int turn = orchestrator.nextTurn(sid);
            orchestrator.prepareTurn(sid, message, turn, model, sink);
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
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("reply", reply);
            return r;
        });

        dispatcher.register("session.list", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.putPOJO("sessionIds", sessions.keySet());
            return r;
        });

        dispatcher.register("shutdown", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("status", "ok");
            return r;
        });
    }

    /** 在给定 stdio 上运行 ACP 协议循环。 */
    public void runLoop(java.io.InputStream in, OutputStream out) throws java.io.IOException {
        dispatcher.runLoop(in, out);
    }
}
