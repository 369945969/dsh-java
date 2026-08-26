package com.deepseek.dsh.session.title;

import java.util.Optional;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 会话标题服务缝 —— 对应原 Harness 的 {@code session-title}。
 *
 * <p>从会话日志生成标题。提供者可注册，支持多种策略：
 * <ul>
 *   <li>首条用户消息截取（无 LLM）</li>
 *   <li>首条用户消息 LLM 摘要</li>
 *   <li>全部用户消息 LLM 摘要</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface SessionTitleService extends Service {

    /** 为给定会话生成标题。 */
    String generate(SessionLog sessionLog);

    /** 获取已生成的标题（若已缓存）。 */
    Optional<String> current(SessionId sessionId);

    /** 注册一个标题（外部设置）。 */
    void setTitle(SessionId sessionId, String title);
}
