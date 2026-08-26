package com.deepseek.dsh.web.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 配置 —— 注册 {@code /ws/agent} 端点。
 *
 * <p>启用原生（非 STOMP）WebSocket，供前端实时双向通信：并发多 session、
 * 流式回复、会话取消。{@link AgentWebSocketHandler} 由 Spring 注入 {@link AgentContextHolder}。
 *
 * <p>设计模式：配置对象（声明式装配）。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
    }

    @Bean
    public TextWebSocketHandler agentWsHandler() {
        return agentWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent").setAllowedOrigins("*");
    }
}
