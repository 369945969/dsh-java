package com.deepseek.dsh.session.stats;

import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 会话统计投影 —— 对应原 Harness 的 {@code session-stats}。
 *
 * <p>从事件日志计算全量会话计数与时长：
 * <ul>
 *   <li>turn 数、step 数</li>
 *   <li>用户/助手消息数</li>
 *   <li>工具调用数</li>
 *   <li>首次/末次事件时间（墙钟时长）</li>
 * </ul>
 *
 * <p>设计模式：投影（Projection）—— 从事件流派生统计视图。
 */
public record SessionStats(
        int turnCount,
        int stepCount,
        int userMessageCount,
        int assistantMessageCount,
        int toolCallCount,
        long firstEventEpoch,
        long lastEventEpoch,
        long wallDurationSeconds
) {
    /** 从会话日志投影统计。 */
    public static SessionStats from(SessionLog log) {
        int turns = 0, steps = 0, user = 0, assistant = 0, tools = 0;
        long first = 0, last = 0;
        for (SessionEvent e : log.snapshot()) {
            switch (e.type()) {
                case TURN_START -> turns++;
                case STEP_START -> steps++;
                case USER_MESSAGE -> user++;
                case ASSISTANT_MESSAGE -> assistant++;
                case TOOL_CALL -> tools++;
                default -> {}
            }
            long ts = e.createdAt() != null ? e.createdAt().getEpochSecond() : 0;
            if (first == 0) first = ts;
            last = ts;
        }
        long dur = (first > 0 && last > 0) ? last - first : 0;
        return new SessionStats(turns, steps, user, assistant, tools, first, last, dur);
    }
}
