package com.deepseek.dsh.sdk.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpc 消息构造与解析测试。
 */
class JsonRpcTest {

    private final JsonRpc rpc = new JsonRpc();

    @Test
    void 请求带id与方法() throws Exception {
        String json = rpc.request("session.create", java.util.Map.of("x", 1));
        var p = rpc.parse(json);
        assertTrue(p.isRequest());
        assertEquals("session.create", p.method());
    }

    @Test
    void 通知无id() throws Exception {
        String json = rpc.notification("session.event", java.util.Map.of());
        var p = rpc.parse(json);
        assertTrue(p.isNotification());
        assertFalse(p.isRequest());
    }

    @Test
    void 响应识别result与error() throws Exception {
        var ok = rpc.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"x\":1}}");
        assertTrue(ok.isResponse());
        assertFalse(ok.error().isPresent());

        var err = rpc.parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"no\"}}");
        assertTrue(err.isResponse());
        assertTrue(err.error().isPresent());
        assertEquals("no", err.error().get());
    }
}
