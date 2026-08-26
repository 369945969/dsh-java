package com.deepseek.dsh.subagent;

/**
 * subagent 委派结果。
 *
 * @param report              委派任务的摘要报告。
 * @param success             是否成功完成。
 * @param childSessionId      子会话 ID（远程会话或本地 fork 会话；无则 null）。
 * @param forwardedEventCount 从子会话转发回的消息事件数（远程会话历史投影条数）。
 */
public record DelegationResult(
        String report,
        boolean success,
        String childSessionId,
        int forwardedEventCount
) {
    /** 兼容旧构造：无子会话标识与转发事件数。 */
    public DelegationResult(String report, boolean success) {
        this(report, success, null, 0);
    }
}
