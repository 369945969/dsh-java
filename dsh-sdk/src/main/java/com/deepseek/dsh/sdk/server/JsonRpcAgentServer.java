package com.deepseek.dsh.sdk.server;

import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.sdk.protocol.JsonRpcDispatcher;

/**
 * JSON-RPC 服务端 —— 对应原 Harness 的 {@code sdk/server}。
 *
 * <p><b>重构后</b>：JSON-RPC 读写/解析/循环逻辑全部委托给共用的 {@link JsonRpcDispatcher}，
 * 仅注册方法处理器，消除此前与 AcpServer 逐字节重复的样板。
 *
 * <p>设计模式：命令注册 + 前端控制器。
 */
public final class JsonRpcAgentServer {

    private final JsonRpcDispatcher dispatcher = new JsonRpcDispatcher();
    private final Context context;
    private final Agent agent;

    public JsonRpcAgentServer(Context context, Agent agent) {
        this.context = context;
        this.agent = agent;
        registerMethods();
    }

    private void registerMethods() {
        dispatcher.register("session.create", (params, ctx) -> {
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("sessionId", UUID.randomUUID().toString());
            return r;
        });

        dispatcher.register("session.run", (params, ctx) -> {
            String sid = params.path("sessionId").asText();
            String message = params.path("message").asText();
            String reply = agent.run(SessionId.of(sid), ScopeKey.random(), context, message);
            ObjectNode r = ctx.mapper().createObjectNode();
            r.put("reply", reply);
            return r;
        });
    }

    /** 在 stdio 上运行 newline-delimited JSON-RPC 循环。 */
    public void run() throws java.io.IOException {
        dispatcher.runLoop(System.in, System.out);
    }
}
