package com.deepseek.dsh.goal;

import java.util.Optional;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;

/**
 * 目标服务能力缝 —— 对应原 Harness 的 {@code ctx.goal}。
 */
public interface Goals extends Service {

    /** 激活一个目标（armed，进程本地）。 */
    Goal arm(SessionId sessionId, String objective);

    /** 查询当前活跃目标。 */
    Optional<Goal> current(SessionId sessionId);

    /** 推进一轮。 */
    Goal advanceRound(SessionId sessionId);

    /** 转换阶段。 */
    Goal setPhase(SessionId sessionId, GoalPhase phase);

    /** 解除目标（disarm，进程本地）。 */
    void disarm(SessionId sessionId);
}
