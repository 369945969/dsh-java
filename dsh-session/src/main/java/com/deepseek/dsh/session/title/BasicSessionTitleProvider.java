package com.deepseek.dsh.session.title;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 基础会话标题提供者 —— 对应原 Harness 的 {@code session-title} 基础策略。
 *
 * <p>不使用 LLM，直接取首条用户消息的前若干字符作为标题。
 * 缓存在内存中，重复查询不重复计算。
 *
 * <p>设计模式：策略的具体实现 + 模板方法（插件基类）。
 */
public final class BasicSessionTitleProvider
        extends AbstractCapabilityPlugin<SessionTitleService>
        implements SessionTitleService {

    private final ConcurrentMap<SessionId, String> cache = new ConcurrentHashMap<>();

    @Override
    protected Class<SessionTitleService> serviceType() {
        return SessionTitleService.class;
    }

    @Override
    public String generate(SessionLog sessionLog) {
        return cache.computeIfAbsent(sessionLog.sessionId(), id -> {
            for (SessionEvent e : sessionLog.snapshot()) {
                if ("user/message".equals(e.type())) {
                    Object content = e.data().get("content");
                    if (content instanceof java.util.List<?> parts && !parts.isEmpty()) {
                        for (Object part : parts) {
                            if (part instanceof java.util.Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t && !t.isBlank()) {
                                return t.length() > 40 ? t.substring(0, 40) + "…" : t;
                            }
                        }
                    }
                }
            }
            return "新会话";
        });
    }

    @Override
    public Optional<String> current(SessionId sessionId) {
        return Optional.ofNullable(cache.get(sessionId));
    }

    @Override
    public void setTitle(SessionId sessionId, String title) {
        cache.put(sessionId, title);
    }
}
