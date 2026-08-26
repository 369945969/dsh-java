package com.deepseek.dsh.schedule;

import java.util.List;
import java.util.Set;

/**
 * 调度事件折叠结果 —— 对应原 Harness 的 {@code FoldedSchedules}。
 *
 * @param active  当前活跃记录（保持原始创建顺序）
 * @param seenIds 本会话局部后缀中曾经创建过的全部 ID
 */
public record FoldedSchedules(
        List<ScheduleRecord> active,
        Set<String> seenIds
) {
    public FoldedSchedules {
        active = List.copyOf(active);
        seenIds = Set.copyOf(seenIds);
    }
}
