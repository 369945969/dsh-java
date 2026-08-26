package com.deepseek.dsh.goal;

/**
 * 目标阶段 —— 对应原 Harness 的 goal lifecycle。
 */
public enum GoalPhase {
    /** 活跃：agent 正在朝此目标推进。 */
    ACTIVE,
    /** 暂停：用户或策略暂停了目标。 */
    PAUSED,
    /** 阻塞：遇到无法推进的障碍。 */
    BLOCKED,
    /** 完成：目标已达成。 */
    COMPLETE
}
