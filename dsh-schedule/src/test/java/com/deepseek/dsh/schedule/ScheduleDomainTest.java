package com.deepseek.dsh.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 调度领域测试 —— 覆盖记录创建、折叠重放、ID 分配、视图、框架、
 * 固定速率发生解析与时间校验。
 */
class ScheduleDomainTest {

    private static final long NOW = Instant.parse("2026-08-26T12:00:00.000Z").toEpochMilli();

    private static ScheduleId id(String v) {
        return ScheduleId.of(v);
    }

    @Test
    void createAfterRecordComputesFutureTarget() {
        ScheduleRecord.After r = ScheduleDomain.createAfterScheduleRecord(
                id("schedule-1"), "  hello  ", 60, NOW);
        assertEquals("hello", r.prompt());
        assertEquals(60, r.afterSeconds());
        assertEquals("2026-08-26T12:01:00.000Z", r.scheduledAt());
    }

    @Test
    void createAfterRejectsEmptyPrompt() {
        ScheduleException ex = assertThrows(ScheduleException.class,
                () -> ScheduleDomain.createAfterScheduleRecord(id("s1"), "   ", 60, NOW));
        assertEquals(ScheduleException.Code.INVALID_PROMPT, ex.code());
    }

    @Test
    void createAfterRejectsNonPositiveDelay() {
        ScheduleException ex = assertThrows(ScheduleException.class,
                () -> ScheduleDomain.createAfterScheduleRecord(id("s1"), "hi", 0, NOW));
        assertEquals(ScheduleException.Code.INVALID_RULE, ex.code());
    }

    @Test
    void createAtFromOffsetString() {
        ScheduleRecord.At r = ScheduleDomain.createAtScheduleRecord(
                id("s1"), "wake", "2026-08-26T13:00:00.000Z", NOW);
        assertEquals("2026-08-26T13:00:00.000Z", r.scheduledAt());
    }

    @Test
    void createAtRejectsPast() {
        ScheduleException ex = assertThrows(ScheduleException.class,
                () -> ScheduleDomain.createAtScheduleRecord(id("s1"), "wake",
                        "2020-01-01T00:00:00.000Z", NOW));
        assertEquals(ScheduleException.Code.NOT_FUTURE, ex.code());
    }

    @Test
    void createAtFromLocalInput() {
        ScheduleRecord.At r = ScheduleDomain.createAtScheduleRecord(
                id("s1"), "wake",
                new LocalAtInput("2026-08-26", "15:00:00", "UTC"), NOW);
        assertEquals("2026-08-26T15:00:00.000Z", r.scheduledAt());
    }

    @Test
    void createAtFromLocalInputWithZone() {
        ScheduleRecord.At r = ScheduleDomain.createAtScheduleRecord(
                id("s1"), "wake",
                new LocalAtInput("2026-08-27", "15:00:00", "Asia/Shanghai"), NOW);
        // Asia/Shanghai is UTC+8, so 15:00 local = 07:00 UTC (next day, future)
        assertEquals("2026-08-27T07:00:00.000Z", r.scheduledAt());
    }

    @Test
    void createAtRejectsBadZone() {
        ScheduleException ex = assertThrows(ScheduleException.class,
                () -> ScheduleDomain.createAtScheduleRecord(id("s1"), "wake",
                        new LocalAtInput("2026-08-26", "15:00:00", "Not/A/Zone/!?"), NOW));
        assertEquals(ScheduleException.Code.INVALID_TIME_ZONE, ex.code());
    }

    @Test
    void createEveryRecordEnforcesMinInterval() {
        ScheduleException ex = assertThrows(ScheduleException.class,
                () -> ScheduleDomain.createEveryScheduleRecord(id("s1"), "hi", 60, NOW));
        assertEquals(ScheduleException.Code.FREQUENCY_TOO_HIGH, ex.code());
    }

    @Test
    void createEveryRecordComputesTarget() {
        ScheduleRecord.Every r = ScheduleDomain.createEveryScheduleRecord(
                id("s1"), "tick", 300, NOW);
        assertEquals(300, r.everySeconds());
        assertEquals("2026-08-26T12:05:00.000Z", r.scheduledAt());
    }

    @Test
    void canonicalizeInstantAcceptsZ() {
        assertEquals("2026-08-26T12:00:00.000Z",
                ScheduleDomain.canonicalizeInstant("2026-08-26T12:00:00Z"));
        assertEquals("2026-08-26T12:00:00.500Z",
                ScheduleDomain.canonicalizeInstant("2026-08-26T12:00:00.5Z"));
    }

    @Test
    void canonicalizeInstantAcceptsOffset() {
        assertEquals("2026-08-26T07:00:00.000Z",
                ScheduleDomain.canonicalizeInstant("2026-08-26T15:00:00+08:00"));
    }

    @Test
    void canonicalizeInstantRejectsBadInput() {
        assertThrows(ScheduleException.class,
                () -> ScheduleDomain.canonicalizeInstant("not-a-date"));
    }

    @Test
    void canonicalizeTimeZoneAcceptsUtcAndIana() {
        assertEquals("UTC", ScheduleDomain.canonicalizeTimeZone("UTC"));
        assertNotNull(ScheduleDomain.canonicalizeTimeZone("America/New_York"));
    }

    @Test
    void resolveEveryOccurrenceAdvancesBySteps() {
        ScheduleRecord.Every every = new ScheduleRecord.Every(
                id("s1"), "tick", 300, "2026-08-26T12:00:00.000Z");
        long acceptedAt = Instant.parse("2026-08-26T12:07:30.000Z").toEpochMilli();
        ScheduleDomain.EveryOccurrence occ = ScheduleDomain.resolveEveryOccurrence(every, acceptedAt);
        assertEquals("2026-08-26T12:05:00.000Z", occ.occurrenceAt());
        assertEquals("2026-08-26T12:10:00.000Z", occ.nextScheduledAt());
    }

    @Test
    void resolveEveryOccurrenceBeforeTargetThrows() {
        ScheduleRecord.Every every = new ScheduleRecord.Every(
                id("s1"), "tick", 300, "2026-08-26T12:05:00.000Z");
        long acceptedAt = Instant.parse("2026-08-26T12:00:00.000Z").toEpochMilli();
        assertThrows(ScheduleException.class,
                () -> ScheduleDomain.resolveEveryOccurrence(every, acceptedAt));
    }

    @Test
    void foldCreateDeleteDispatch() {
        ScheduleRecord.After a = ScheduleDomain.createAfterScheduleRecord(
                id("s1"), "a", 60, NOW);
        ScheduleRecord.At b = ScheduleDomain.createAtScheduleRecord(
                id("s2"), "b", "2026-08-26T13:00:00.000Z", NOW);

        FoldedSchedules f1 = ScheduleDomain.foldChanges(List.of(
                new ScheduleChange.Create(a),
                new ScheduleChange.Create(b)));
        assertEquals(2, f1.active().size());
        assertEquals(2, f1.seenIds().size());

        FoldedSchedules f2 = ScheduleDomain.foldChanges(List.of(
                new ScheduleChange.Create(a),
                new ScheduleChange.Create(b),
                new ScheduleChange.Delete(id("s1"))));
        assertEquals(1, f2.active().size());
        assertEquals(id("s2"), f2.active().get(0).id());
        assertTrue(f2.seenIds().contains("s1"));

        FoldedSchedules f3 = ScheduleDomain.foldChanges(List.of(
                new ScheduleChange.Create(a),
                new ScheduleChange.Create(b),
                new ScheduleChange.Delete(id("s1")),
                ScheduleChange.Dispatch.oneShot(id("s2"))));
        assertTrue(f3.active().isEmpty());
    }

    @Test
    void foldEveryDispatchAdvancesScheduledAt() {
        ScheduleRecord.Every every = new ScheduleRecord.Every(
                id("s1"), "tick", 300, "2026-08-26T12:00:00.000Z");
        long acceptedAt = Instant.parse("2026-08-26T12:07:30.000Z").toEpochMilli();
        String acceptedAtStr = "2026-08-26T12:07:30.000Z";
        FoldedSchedules folded = ScheduleDomain.foldChanges(List.of(
                new ScheduleChange.Create(every),
                ScheduleChange.Dispatch.every(id("s1"), acceptedAtStr)));
        assertEquals(1, folded.active().size());
        ScheduleRecord.Every next = (ScheduleRecord.Every) folded.active().get(0);
        assertEquals("2026-08-26T12:10:00.000Z", next.scheduledAt());
    }

    @Test
    void foldRejectsReusedId() {
        ScheduleRecord.After a = ScheduleDomain.createAfterScheduleRecord(
                id("s1"), "a", 60, NOW);
        ScheduleRecord.After a2 = ScheduleDomain.createAfterScheduleRecord(
                id("s1"), "a2", 60, NOW);
        assertThrows(ScheduleException.class,
                () -> ScheduleDomain.foldChanges(List.of(
                        new ScheduleChange.Create(a),
                        new ScheduleChange.Create(a2))));
    }

    @Test
    void foldRejectsDeleteInactive() {
        assertThrows(ScheduleException.class,
                () -> ScheduleDomain.foldChanges(List.of(
                        new ScheduleChange.Delete(id("s1")))));
    }

    @Test
    void allocateScheduleIdAvoidsSeen() {
        FoldedSchedules folded = new FoldedSchedules(
                List.of(), java.util.Set.of("schedule-1", "schedule-2"));
        assertEquals(id("schedule-3"), ScheduleDomain.allocateScheduleId(folded));
    }

    @Test
    void scheduleViewState() {
        ScheduleRecord.At future = new ScheduleRecord.At(
                id("s1"), "wake", "2026-08-26T13:00:00.000Z");
        ScheduleDomain.ScheduleView v = ScheduleDomain.scheduleView(future, NOW);
        assertEquals("scheduled", v.state());
        assertEquals("session-local", v.deliveryMode());

        long later = Instant.parse("2026-08-26T14:00:00.000Z").toEpochMilli();
        assertEquals("overdue", ScheduleDomain.scheduleView(future, later).state());
    }

    @Test
    void renderReminderFramingContainsIdAndPrompt() {
        ScheduleRecord.At r = new ScheduleRecord.At(
                id("s1"), "wake up", "2026-08-26T13:00:00.000Z");
        String framing = ScheduleDomain.renderReminderFraming(r);
        assertTrue(framing.contains("[SCHEDULE REMINDER]"));
        assertTrue(framing.contains("s1"));
        assertTrue(framing.contains("wake up"));
    }

    @Test
    void renderEveryBatchFramingContainsAllReminders() {
        ScheduleRecord.Every e = new ScheduleRecord.Every(
                id("s1"), "tick", 300, "2026-08-26T12:00:00.000Z");
        String framing = ScheduleDomain.renderEveryReminderBatchFraming(
                List.of(new ScheduleDomain.EveryDue(e, "2026-08-26T12:05:00.000Z")));
        assertTrue(framing.contains("[SCHEDULE REMINDER BATCH]"));
        assertTrue(framing.contains("tick"));
    }

    @Test
    void recordKindDiscriminator() {
        assertEquals("after", new ScheduleRecord.After(id("s1"), "p", 1, "2026-08-26T13:00:00.000Z").kind());
        assertEquals("at", new ScheduleRecord.At(id("s1"), "p", "2026-08-26T13:00:00.000Z").kind());
        assertEquals("every", new ScheduleRecord.Every(id("s1"), "p", 300, "2026-08-26T13:00:00.000Z").kind());
    }
}
