package com.deepseek.dsh.plan;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 计划模式服务 —— 对应原 Harness 的 {@code plan-mode}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractCapabilityPlugin}，消除样板。
 *
 * <p>plan mode 是一种日志化的 agent 状态：进入后 agent 只规划不执行工具，
 * 产出计划草案；退出需经 reviewed（用户审阅确认）。
 *
 * <p>设计模式：状态机 + 备忘录 + 模板方法。
 */
public final class PlanModeService extends AbstractCapabilityPlugin<PlanMode> implements PlanMode {

    private final ConcurrentMap<SessionId, PlanState> states = new ConcurrentHashMap<>();

    @Override
    protected Class<PlanMode> serviceType() {
        return PlanMode.class;
    }

    @Override
    public void enter(SessionId sessionId) {
        states.put(sessionId, new PlanState(true, null, false));
    }

    @Override
    public void setPlan(SessionId sessionId, String plan) {
        states.computeIfPresent(sessionId, (k, s) -> s.withPlan(plan));
    }

    @Override
    public boolean isInPlanMode(SessionId sessionId) {
        return states.getOrDefault(sessionId, PlanState.INACTIVE).active();
    }

    @Override
    public Optional<String> currentPlan(SessionId sessionId) {
        PlanState s = states.get(sessionId);
        return s != null ? Optional.ofNullable(s.plan()) : Optional.empty();
    }

    @Override
    public boolean exit(SessionId sessionId, boolean approved) {
        PlanState s = states.get(sessionId);
        if (s == null || !s.active()) return true;
        if (approved) {
            states.remove(sessionId);
            return true;
        }
        // 未批准：保持 plan mode
        return false;
    }

    /** 计划状态值对象。 */
    private record PlanState(boolean active, String plan, boolean reviewed) {
        static final PlanState INACTIVE = new PlanState(false, null, false);

        PlanState withPlan(String newPlan) {
            return new PlanState(active, newPlan, reviewed);
        }
    }
}
