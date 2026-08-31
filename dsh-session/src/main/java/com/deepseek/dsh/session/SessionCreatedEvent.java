package com.deepseek.dsh.session;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 会话创建事件 —— 在{@code Sessions}服务新建一个会话时，通过所在 {@link com.deepseek.dsh.core.context.Context}
 * 的 {@link com.deepseek.dsh.core.event.EventBus} 广播。
 *
 * <p>用途：让同进程内的观察者（如 web 网关）获知新会话出现，进而向已连接的前端实时推送
 * {@code host/session-added} 帧，使会话列表无需刷新即可更新。设计范围限定于<em>同进程</em>
 * （{@link com.deepseek.dsh.session.SessionManager} 所在 JVM）；独立 JVM 的 RPC/ACP/CLI 入口不在此列。
 *
 * <p>发射点：
 * <ul>
 *   <li>{@link Sessions#create()} —— 显式新建（web 建会话、fork 子会话等）。</li>
 *   <li>{@link Sessions#getOrCreate(SessionId)} 物化出一个<em>全新空白</em>会话（如进程内子 agent 委派），
 *       此时日志为空（非持久化重放），故视为新会话。</li>
 * </ul>
 *
 * <p>设计模式：观察者（事件负载）+ 值对象。
 */
public record SessionCreatedEvent(SessionId sessionId) {

    public SessionCreatedEvent {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
    }
}
