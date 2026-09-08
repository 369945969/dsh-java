package com.deepseek.dsh.web.server;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手拦截器 —— 把 HTTP header（X-DSH-AppId / X-DSH-UserId）拷贝到
 * 握手 attributes，供 {@link AgentWebSocketHandler} 在每个 turn 注入 SessionAuth。
 *
 * <p>WS 连接建立时只有握手阶段能拿到 HTTP header；握手后消息帧无 header，
 * 故在此一次性提取存入 attributes。
 *
 * <p>设计模式：拦截器（在协议升级边界传递上下文）。
 */
@Component
public class SessionAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String APPID_ATTR = "X-DSH-APPID";
    public static final String USERID_ATTR = "X-DSH-USERID";
    public static final String REASONING_ATTR = "X-DSH-REASONING";
    public static final String MODEL_ATTR = "X-DSH-MODEL";
    public static final String WORKSPACE_ATTR = "X-DSH-WORKSPACE";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String appid = firstHeader(request, "X-DSH-APPID");
        String userid = firstHeader(request, "X-DSH-USERID");
        String reasoning = firstHeader(request, "X-DSH-REASONING");
        String model = firstHeader(request, "X-DSH-MODEL");
        String workspace = firstHeader(request, "X-DSH-WORKSPACE");
        if (appid != null) attributes.put(APPID_ATTR, appid);
        if (userid != null) attributes.put(USERID_ATTR, userid);
        if (reasoning != null) attributes.put(REASONING_ATTR, reasoning);
        if (model != null) attributes.put(MODEL_ATTR, model);
        if (workspace != null) attributes.put(WORKSPACE_ATTR, workspace);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String firstHeader(ServerHttpRequest request, String name) {
        var values = request.getHeaders().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
