package com.deepseek.dsh.session;

import java.util.List;
import java.util.Optional;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 会话服务能力缝 —— 对应原 Harness 的 {@code ctx.sessions}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@link SessionManager}（内存 + 持久化协调）。</li>
 *   <li><b>消费者</b>：agent loop 通过 {@code ctx.require(Sessions.class)} 获取。</li>
 * </ul>
 */
public interface Sessions extends Service {

    /** 创建一个新会话。 */
    SessionLog create();

    /** 获取活跃会话（若存在）。 */
    Optional<SessionLog> get(SessionId id);

    /** 获取或创建会话；创建时从持久化后端重放历史。 */
    SessionLog getOrCreate(SessionId id);

    /** 持久化一条事件到后端。 */
    void persist(SessionEvent event);

    /** 列出全部会话 ID（活跃 + 持久化）。 */
    List<SessionId> list();
}
