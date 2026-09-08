package com.deepseek.dsh.web.api;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 发送消息响应 DTO —— 包含最终回复与完整对话历史。
 *
 * @param appid         会话所属应用（从 header 注入或默认 "default"）
 * @param userid        会话所属用户（从 header 注入或默认 ""）
 * @param reasoning     推理标记（true/false/auto，从 header 注入或默认 "auto"）
 * @param modelId       本会话使用的模型 id（从 header 注入或默认 ""=活跃档案模型）
 * @param workspaceId   所属工作区 id（从 header 注入或默认 ""）
 * @param inputTokens   本会话累计输入 token（prompt）
 * @param outputTokens  本会话累计输出 token（completion）
 * @param sessionTokens 本会话累计总 token（input+output，供客户端判断何时手动压缩）
 */
public record SendMessageResponse(
        String sessionId,
        String reply,
        List<ChatMessage> history,
        long totalTokens,
        String appid,
        String userid,
        String reasoning,
        String modelId,
        String workspaceId,
        long inputTokens,
        long outputTokens,
        long sessionTokens
) {}
