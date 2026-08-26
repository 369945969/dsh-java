package com.deepseek.dsh.agent.state;

/**
 * agent 循环是否应继续执行下一步的决策。
 */
public enum ContinueDecision {
    /** 继续：模型还欠工具调用，或新输入到达。 */
    CONTINUE,
    /** 停止：模型已 stop 且无待处理工具调用。 */
    STOP,
    /** 终止：外层策略介入（如超时、目标达成）。 */
    TERMINATE
}
