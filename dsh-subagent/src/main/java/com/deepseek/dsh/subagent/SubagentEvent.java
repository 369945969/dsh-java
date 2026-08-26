package com.deepseek.dsh.subagent;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 子 agent 生命周期事件 —— 通过父 Context 的 {@link com.deepseek.dsh.core.event.EventBus}
 * 转发给父 agent，使其可观察子 agent 的委派生命周期与会话事件。
 *
 * <p>对应原 Harness 的 subagent lifecycle notification forwarding（ACP 远程子 agent）。
 * 单一事件类型 + {@link Kind} 区分三种状态，便于用一次
 * {@code ctx.events().on(SubagentEvent.class, ...)} 统一订阅：
 * <ul>
 *   <li>{@link Kind#SPAWNED} —— 委派开始（子会话已创建/复用，即将执行）。{@code detail} 为任务预览。</li>
 *   <li>{@link Kind#COMPLETED} —— 委派成功。{@code forwardedEventCount} 为从子会话转发的消息事件数，{@code detail} 为报告预览。</li>
 *   <li>{@link Kind#FAILED} —— 委派失败。{@code detail} 为错误信息。</li>
 * </ul>
 *
 * <p>设计模式：观察者（事件负载）+ 值对象。
 */
public record SubagentEvent(
        Kind kind,
        SessionId parentSessionId,
        String childSessionId,
        String persona,
        int forwardedEventCount,
        String detail
) {
    /** 委派生命周期状态。 */
    public enum Kind { SPAWNED, COMPLETED, FAILED }

    public SubagentEvent {
        if (kind == null) kind = Kind.SPAWNED;
        if (forwardedEventCount < 0) forwardedEventCount = 0;
    }
}
