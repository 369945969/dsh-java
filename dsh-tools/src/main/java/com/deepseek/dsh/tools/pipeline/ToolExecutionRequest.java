package com.deepseek.dsh.tools.pipeline;

import java.util.Map;

import com.deepseek.dsh.tools.registry.ToolContext;

/**
 * 工具执行请求 —— 贯穿执行管线的负载。
 *
 * @param toolName    工具名
 * @param toolCallId  工具调用 ID
 * @param arguments   模型提供的参数
 * @param context     工具执行上下文（贯穿中间件链，替代 ThreadLocal）
 */
public record ToolExecutionRequest(
        String toolName,
        String toolCallId,
        Map<String, Object> arguments,
        ToolContext context
) {}
