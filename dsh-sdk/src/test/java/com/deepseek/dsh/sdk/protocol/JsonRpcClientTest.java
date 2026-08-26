package com.deepseek.dsh.sdk.protocol;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcClient 行分隔 JSON-RPC 传输测试 —— 与 JsonRpcDispatcher 进程内管道往返。
 */
class JsonRpcClientTest {

    private JsonRpcClient startPeer(JsonRpcDispatcher server) throws Exception {
        PipedInputStream serverIn = new PipedInputStream(65536);
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        PipedInputStream clientIn = new PipedInputStream(65536);
        PipedOutputStream serverOut = new PipedOutputStream(clientIn);
        Thread.startVirtualThread(() -> {
            try { server.runLoop(serverIn, serverOut); } catch (Exception ignored) {}
        });
        return new JsonRpcClient(clientIn, clientOut);
    }

    @Test
    void 请求响应往返() throws Exception {
        var dispatcher = new JsonRpcDispatcher();
        dispatcher.register("echo", (params, ctx) ->
                ctx.mapper().createObjectNode().put("v", params.path("v").asInt(0)));
        try (var client = startPeer(dispatcher)) {
            var result = client.request("echo", Map.of("v", 42)).join();
            assertEquals(42, result.path("v").asInt());
        }
    }

    @Test
    void 错误帧转JsonRpcException() throws Exception {
        var dispatcher = new JsonRpcDispatcher();
        dispatcher.register("boom", (params, ctx) -> { throw new RuntimeException("炸了"); });
        try (var client = startPeer(dispatcher)) {
            var f = client.request("boom", Map.of());
            var ex = assertThrows(java.util.concurrent.CompletionException.class, f::join);
            assertTrue(ex.getCause() instanceof JsonRpcException);
            assertEquals(-32603, ((JsonRpcException) ex.getCause()).code());
        }
    }

    @Test
    void 未知方法返回32601() throws Exception {
        var dispatcher = new JsonRpcDispatcher();
        try (var client = startPeer(dispatcher)) {
            var f = client.request("nope", Map.of());
            var ex = assertThrows(java.util.concurrent.CompletionException.class, f::join);
            assertEquals(-32601, ((JsonRpcException) ex.getCause()).code());
        }
    }

    @Test
    void 通知不期待响应() throws Exception {
        var dispatcher = new JsonRpcDispatcher();
        var hit = new java.util.concurrent.atomic.AtomicBoolean(false);
        dispatcher.register("ping", (params, ctx) -> { hit.set(true); return ctx.mapper().createObjectNode(); });
        try (var client = startPeer(dispatcher)) {
            client.notify("ping", Map.of());
            // 等待处理
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(300);
            assertTrue(hit.get(), "通知应被处理");
        }
    }
}
