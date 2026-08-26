package com.deepseek.dsh.schedule;

import com.deepseek.dsh.core.brand.Branded;

/**
 * 调度提醒 ID —— 会话局部稳定且永不复用的品牌化字符串标识，
 * 对应原 Harness 的 {@code ScheduleId}。
 *
 * <p>格式形如 {@code schedule-1}、{@code schedule-2}，由 {@link ScheduleDomain#allocateScheduleId}
 * 在折叠后的已见 ID 集合之外分配。
 */
public final class ScheduleId extends Branded<String, ScheduleId.Tag> {
    private ScheduleId(String value) {
        super(value);
    }

    public static ScheduleId of(String raw) {
        return new ScheduleId(raw);
    }

    public static final class Tag {}
}
