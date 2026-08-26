package com.deepseek.dsh.plan;

import java.util.Optional;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;

/**
 * 计划模式能力缝 —— 对应原 Harness 的 {@code plan-mode}。
 *
 * <p>进入计划模式后 agent 只规划不执行；退出需经用户审阅（reviewed exit）。
 */
public interface PlanMode extends Service {

    /** 进入计划模式。 */
    void enter(SessionId sessionId);

    /** 设置当前计划草案。 */
    void setPlan(SessionId sessionId, String plan);

    /** 是否处于计划模式。 */
    boolean isInPlanMode(SessionId sessionId);

    /** 获取当前计划草案。 */
    Optional<String> currentPlan(SessionId sessionId);

    /**
     * 退出计划模式。
     *
     * @param approved 用户是否批准该计划
     * @return true 表示已退出；false 表示未批准，保持计划模式
     */
    boolean exit(SessionId sessionId, boolean approved);
}
