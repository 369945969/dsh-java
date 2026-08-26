package com.deepseek.dsh.schedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.deepseek.dsh.session.log.SessionLog;

/**
 * 调度运行时 —— 一个会话的活跃定时器投影，对应原 Harness 的 {@code ScheduleRuntime}。
 *
 * <p>原 TS 版与完整 agent 生命周期紧耦合（{@code agent.runMaintenance}、
 * {@code agent.followup}、{@code agent.whenIdle}、{@code ctx.sessions.flush}）。
 * 本 Java 移植把领域决策抽离为纯方法：折叠当前会话事件、选择下一个到期动作
 * （一次性/固定速率批量/等待），并产出要追加的派发变更与模型框架文本。
 * 实际定时器武装与 agent 注入由宿主在 {@link DueDecision} 结果上接线。
 *
 * <p>设计模式：投影（活跃状态派生）+ 决策（到期选择）。
 */
public final class ScheduleRuntime {

    private final SessionLog session;

    public ScheduleRuntime(SessionLog session) {
        this.session = session;
    }

    /** 折叠当前会话的调度事件后缀。 */
    public FoldedSchedules readFolded() {
        return ScheduleDomain.foldChanges(ScheduleChangeCodec.extract(session.snapshot()));
    }

    /**
     * 选择一个到期的一次性、一个完整的固定速率批量，或下一个等待目标。
     *
     * @param now 墙钟采样（epoch 毫秒）
     */
    public DueDecision decide(long now) {
        FoldedSchedules folded = readFolded();
        List<ScheduleRecord> active = folded.active();

        List<Indexed> indexed = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            indexed.add(new Indexed(active.get(i), i));
        }
        Comparator<Indexed> byTargetThenCreate = Comparator
                .comparingLong((Indexed e) -> Instant.parse(e.record.scheduledAt()).toEpochMilli())
                .thenComparingInt(e -> e.index);

        List<Indexed> oneShotOverdue = indexed.stream()
                .filter(e -> !(e.record instanceof ScheduleRecord.Every)
                        && Instant.parse(e.record.scheduledAt()).toEpochMilli() <= now)
                .sorted(byTargetThenCreate)
                .toList();
        if (!oneShotOverdue.isEmpty()) {
            return new DueDecision.OneShot(oneShotOverdue.get(0).record);
        }

        List<Indexed> everyOverdue = indexed.stream()
                .filter(e -> e.record instanceof ScheduleRecord.Every
                        && Instant.parse(e.record.scheduledAt()).toEpochMilli() <= now)
                .sorted(byTargetThenCreate)
                .toList();
        if (!everyOverdue.isEmpty()) {
            String acceptedAt = ScheduleDomain.CANONICAL_UTC.format(Instant.ofEpochMilli(now));
            List<ScheduleDomain.EveryDue> reminders = everyOverdue.stream()
                    .map(e -> {
                        ScheduleRecord.Every every = (ScheduleRecord.Every) e.record;
                        return new ScheduleDomain.EveryDue(every,
                                ScheduleDomain.resolveEveryOccurrence(every, now).occurrenceAt());
                    })
                    .toList();
            return new DueDecision.Every(acceptedAt, reminders);
        }

        long target = Long.MAX_VALUE;
        boolean found = false;
        for (ScheduleRecord record : active) {
            long candidate = Instant.parse(record.scheduledAt()).toEpochMilli();
            if (candidate > now && candidate < target) {
                target = candidate;
                found = true;
            }
        }
        return found ? new DueDecision.Wait(target) : new DueDecision.Wait(null);
    }

    /** 渲染一个到期决策的模型框架文本。 */
    public static String renderFraming(DueDecision decision) {
        return switch (decision) {
            case DueDecision.OneShot one -> ScheduleDomain.renderReminderFraming(one.record());
            case DueDecision.Every every -> ScheduleDomain.renderEveryReminderBatchFraming(every.reminders());
            case DueDecision.Wait ignored -> null;
        };
    }

    /** 为一次到期决策产出要追加的派发变更。 */
    public static List<ScheduleChange> dispatchChangesFor(DueDecision decision) {
        return switch (decision) {
            case DueDecision.OneShot one -> List.<ScheduleChange>of(ScheduleChange.Dispatch.oneShot(one.record().id()));
            case DueDecision.Every every -> every.reminders().stream()
                    .<ScheduleChange>map(r -> ScheduleChange.Dispatch.every(r.record().id(), every.acceptedAt()))
                    .toList();
            case DueDecision.Wait ignored -> List.of();
        };
    }

    private record Indexed(ScheduleRecord record, int index) {
    }

    /** 到期决策联合。 */
    public sealed interface DueDecision permits DueDecision.OneShot, DueDecision.Every, DueDecision.Wait {
        /** 一次性到期提醒。 */
        record OneShot(ScheduleRecord record) implements DueDecision {
        }

        /** 固定速率批量到期提醒。 */
        record Every(String acceptedAt, List<ScheduleDomain.EveryDue> reminders) implements DueDecision {
            public Every {
                reminders = List.copyOf(reminders);
            }
        }

        /** 等待：下一个未来目标（无活跃记录时为 {@code null}）。 */
        record Wait(Long target) implements DueDecision {
        }
    }
}
