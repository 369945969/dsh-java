package com.deepseek.dsh.tools.registry;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.context.Context;

/**
 * 工具执行上下文 —— 在工具执行期间可用的运行时依赖。
 *
 * <p>提供会话 ID、agent 作用域键、底层插件上下文，便于工具访问其他服务
 * （如审批、shell 能力、文件系统能力）。
 */
public record ToolContext(
        SessionId sessionId,
        ScopeKey scopeKey,
        Context context
) {}
