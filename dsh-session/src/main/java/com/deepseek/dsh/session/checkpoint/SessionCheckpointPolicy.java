package com.deepseek.dsh.session.checkpoint;

/**
 * 会话检查点策略 —— 对应原 Harness 的 {@code session-checkpoint-policy}。
 *
 * <p>决定在哪些语义节点触发持久化检查点（确保崩溃后可恢复）：
 * <ul>
 *   <li>模型请求前（保证上下文已持久化）</li>
 *   <li>工具副作用后（保证工具结果不丢失）</li>
 *   <li>turn 结束时</li>
 * </ul>
 *
 * <p>设计模式：策略（检查点触发策略可插拔）。
 */
public interface SessionCheckpointPolicy {

    /** 是否应在模型请求前触发检查点。 */
    boolean beforeModelRequest();

    /** 是否应在工具副作用后触发检查点。 */
    boolean afterToolSideEffect();

    /** 是否应在 turn 结束时触发检查点。 */
    boolean onTurnEnd();

    /** 默认策略：模型请求前 + 工具副作用后。 */
    final class Default implements SessionCheckpointPolicy {
        @Override public boolean beforeModelRequest() { return true; }
        @Override public boolean afterToolSideEffect() { return true; }
        @Override public boolean onTurnEnd() { return false; }
    }
}
