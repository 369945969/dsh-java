package com.deepseek.dsh.context.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * 跨会话快照引用插件 —— 对应原 Harness 的 {@code dsh-session-reference}。
 *
 * <p>用户在消息中引用其他会话（如 @session:abc-123）时，解析引用 →
 * 加载被引会话的标题 + 最近消息摘要 → 以 {@code <system-reminder>} 注入。
 *
 * <p>设计模式：观察者（监听系统提示注入）+ 策略（会话引用解析）。
 */
public final class SessionReferencePlugin implements Plugin, Service {

    private static final Logger log = LoggerFactory.getLogger(SessionReferencePlugin.class);

    /** 会话引用格式：@session:<id> 或 @s:<id> */
    private static final Pattern REFERENCE_PATTERN =
            Pattern.compile("@(?:session|s):([a-zA-Z0-9_-]+)");

    private com.deepseek.dsh.session.Sessions sessions;

    @Override
    public Disposable apply(Context ctx) {
        sessions = ctx.get(com.deepseek.dsh.session.Sessions.class).orElse(null);

        ctx.events().on(SystemPromptInjectEvent.class, (event, next) -> {
            // 从最近的用户消息中解析引用
            String references = resolveReferences(ctx);
            if (references != null && !references.isBlank()) {
                event.appendSection("session-reference", references);
            }
            return next.invoke(event);
        });
        return () -> {};
    }

    /** 解析引用会话并渲染摘要。 */
    private String resolveReferences(Context ctx) {
        if (sessions == null) return null;

        // 获取最近的用户消息文本（从会话日志投影）
        com.deepseek.dsh.session.log.SessionLog slog = null;
        // 此处简化：从 ctx 无法直接拿当前 sessionId，跳过
        // 完整实现需通过 TurnContext 传入 sessionId
        return null;
    }
}
