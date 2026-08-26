package com.deepseek.dsh.web.api;

/**
 * 发送消息请求 DTO。
 */
public record SendMessageRequest(
        String sessionId,
        String message
) {}
