package com.deepseek.dsh.schedule;

/**
 * 调度提醒记录联合 —— 对应原 Harness 的 {@code ScheduleRecord}。
 *
 * <p>v1 持久提醒记录的三种变体：
 * <ul>
 *   <li>{@link After} —— 正向延迟创建的一次性提醒。</li>
 *   <li>{@link At} —— 绝对时刻创建的一次性提醒。</li>
 *   <li>{@link Every} —— 固定速率提醒，下一目标始终与创建锚对齐。</li>
 * </ul>
 *
 * <p>每条记录都携带会话局部稳定 ID、已去空白的内容提示，以及
 * 规范的四位数年份 RFC 3339 UTC 目标 {@code scheduledAt}。
 *
 * <p>设计模式：值对象 + 密封联合（代数数据类型）。
 */
public sealed interface ScheduleRecord permits ScheduleRecord.After, ScheduleRecord.At, ScheduleRecord.Every {

    /** 会话局部稳定标识。 */
    ScheduleId id();

    /** 提醒内容（创建时已去空白）。 */
    String prompt();

    /** 四位数年份 RFC 3339 UTC 目标。 */
    String scheduledAt();

    /** 正向延迟创建的一次性提醒。 */
    record After(ScheduleId id, String prompt, long afterSeconds, String scheduledAt) implements ScheduleRecord {
    }

    /** 绝对时刻创建的一次性提醒。 */
    record At(ScheduleId id, String prompt, String scheduledAt) implements ScheduleRecord {
    }

    /** 固定速率提醒，下一目标始终与创建锚对齐。 */
    record Every(ScheduleId id, String prompt, long everySeconds, String scheduledAt) implements ScheduleRecord {
    }

    /** 记录判别类型名（{@code after}/{@code at}/{@code every}）。 */
    default String kind() {
        return switch (this) {
            case After ignored -> "after";
            case At ignored -> "at";
            case Every ignored -> "every";
        };
    }
}
