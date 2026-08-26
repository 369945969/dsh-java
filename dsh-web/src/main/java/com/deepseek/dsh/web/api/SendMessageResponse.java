package com.deepseek.dsh.web.api;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 发送消息响应 DTO —— 包含最终回复与完整对话历史。
 */
public record SendMessageResponse(
        String sessionId,
        String reply,
        List<ChatMessage> history,
        long totalTokens
) {}
