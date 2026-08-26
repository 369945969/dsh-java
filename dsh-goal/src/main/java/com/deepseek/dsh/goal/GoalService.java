package com.deepseek.dsh.goal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 目标服务能力缝 —— 对应原 Harness 的 {@code ctx.goal}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractCapabilityPlugin}，消除样板。
 *
 * <p>设计模式：注册表 + 状态机管理器 + 模板方法（插件基类）。
 */
public final class GoalService extends AbstractCapabilityPlugin<Goals> implements Goals {

    private final ConcurrentMap<SessionId, Goal> activeGoals = new ConcurrentHashMap<>();

    @Override
    protected Class<Goals> serviceType() {
        return Goals.class;
    }

    @Override
    public Goal arm(SessionId sessionId, String objective) {
        Goal g = Goal.armed(sessionId, objective);
        activeGoals.put(sessionId, g);
        return g;
    }

    @Override
    public Optional<Goal> current(SessionId sessionId) {
        return Optional.ofNullable(activeGoals.get(sessionId));
    }

    @Override
    public Goal advanceRound(SessionId sessionId) {
        return activeGoals.computeIfPresent(sessionId, (k, g) -> g.advanceRound());
    }

    @Override
    public Goal setPhase(SessionId sessionId, GoalPhase phase) {
        return activeGoals.computeIfPresent(sessionId, (k, g) -> g.withPhase(phase));
    }

    @Override
    public void disarm(SessionId sessionId) {
        activeGoals.remove(sessionId);
    }
}
