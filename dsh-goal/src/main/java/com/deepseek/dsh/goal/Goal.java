package com.deepseek.dsh.goal;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 目标 —— 同会话内的持久化目标，对应原 Harness 的 goal。
 *
 * <p>目标有生命周期阶段（active/paused/blocked/complete），驱动 goal-round 迭代。
 * {@code activation} 是进程本地的（armed/disarmed），不出现在持久化回放中。
 */
public record Goal(
        /** 目标所属会话。 */
        SessionId sessionId,
        /** 目标文本描述。 */
        String objective,
        /** 当前阶段。 */
        GoalPhase phase,
        /** 已进行的 goal-round 数。 */
        int roundsCompleted,
        /** 每轮的最多 step 数上限。 */
        int maxRoundsPerGoal
) {
    public static Goal armed(SessionId sessionId, String objective) {
        return new Goal(sessionId, objective, GoalPhase.ACTIVE, 0, 5);
    }

    /** 进入下一轮。 */
    public Goal advanceRound() {
        return new Goal(sessionId, objective, phase, roundsCompleted + 1, maxRoundsPerGoal);
    }

    /** 转换阶段。 */
    public Goal withPhase(GoalPhase newPhase) {
        return new Goal(sessionId, objective, newPhase, roundsCompleted, maxRoundsPerGoal);
    }

    /** 是否已达轮数上限。 */
    public boolean exhausted() {
        return roundsCompleted >= maxRoundsPerGoal;
    }
}
