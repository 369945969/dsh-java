package com.deepseek.dsh.schedule;

/**
 * 严格的版本-1 调度变更联合 —— 对应原 Harness 的 {@code ScheduleChange}。
 *
 * <p>会话事件 {@code schedule/change} 的持久负载。三种操作：
 * <ul>
 *   <li>{@link Create} —— 创建一条提醒记录。</li>
 *   <li>{@link Delete} —— 删除一条当前活跃的提醒。</li>
 *   <li>{@link Dispatch} —— 记录一次活跃提醒进入派发历史。</li>
 * </ul>
 *
 * <p>一次性提醒派发只携带 id；固定速率派发额外携带 {@code acceptedAt}
 * （墙钟决策时刻），用于选择最近的到期发生并直接跳过被错过的发生。
 *
 * <p>设计模式：值对象 + 密封联合（代数数据类型）。
 */
public sealed interface ScheduleChange permits ScheduleChange.Create, ScheduleChange.Delete, ScheduleChange.Dispatch {

    /** 协议版本（恒为 1）。 */
    int version();

    /** 操作名。 */
    String operation();

    /** 创建一条提醒记录。 */
    record Create(ScheduleRecord schedule) implements ScheduleChange {
        @Override public int version() { return 1; }
        @Override public String operation() { return "create"; }
    }

    /** 删除一条当前活跃的提醒。 */
    record Delete(ScheduleId id) implements ScheduleChange {
        @Override public int version() { return 1; }
        @Override public String operation() { return "delete"; }
    }

    /**
     * 记录一次派发。
     *
     * @param id        被派发的提醒 ID
     * @param acceptedAt 仅固定速率派发时的墙钟决策时刻；一次性派发为 {@code null}
     */
    record Dispatch(ScheduleId id, String acceptedAt) implements ScheduleChange {
        @Override public int version() { return 1; }
        @Override public String operation() { return "dispatch"; }

        /** 一次性派发（仅 id）。 */
        public static Dispatch oneShot(ScheduleId id) {
            return new Dispatch(id, null);
        }

        /** 固定速率派发（id + acceptedAt）。 */
        public static Dispatch every(ScheduleId id, String acceptedAt) {
            return new Dispatch(id, acceptedAt);
        }
    }
}
