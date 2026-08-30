package com.deepseek.dsh.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 调度运行时测试 —— 覆盖到期决策（等待/一次性/批量）与框架/派发变更产出。
 */
class ScheduleRuntimeTest {

    private static final long NOW = Instant.parse("2026-08-26T12:00:00.000Z").toEpochMilli();

    private static SessionLog sessionWith(ScheduleChange... changes) {
        SessionLog session = new SessionLog(SessionId.of("ses-rt"));
        for (ScheduleChange c : changes) {
            session.append(ScheduleChangeCodec.EVENT_TYPE,
                    ScheduleChangeCodec.encode(c));
        }
        return session;
    }

    @Test
    void decideWaitWhenNothingDue() {
        ScheduleRecord.At future = new ScheduleRecord.At(
                ScheduleId.of("s1"), "wake", "2026-08-26T13:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(new ScheduleChange.Create(future)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertInstanceOf(ScheduleRuntime.DueDecision.Wait.class, decision);
        assertEquals(Instant.parse("2026-08-26T13:00:00.000Z").toEpochMilli(),
                ((ScheduleRuntime.DueDecision.Wait) decision).target().longValue());
    }

    @Test
    void decideWaitWithNoActiveReturnsNullTarget() {
        ScheduleRuntime rt = new ScheduleRuntime(new SessionLog(SessionId.of("ses-empty")));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertInstanceOf(ScheduleRuntime.DueDecision.Wait.class, decision);
        assertNull(((ScheduleRuntime.DueDecision.Wait) decision).target());
    }

    @Test
    void decideOneShotWhenOverdue() {
        ScheduleRecord.At past = new ScheduleRecord.At(
                ScheduleId.of("s1"), "wake", "2026-08-26T11:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(new ScheduleChange.Create(past)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertInstanceOf(ScheduleRuntime.DueDecision.OneShot.class, decision);
        assertEquals("s1", ((ScheduleRuntime.DueDecision.OneShot) decision).record().id().value());
    }

    @Test
    void decideEveryBatchWhenOverdue() {
        ScheduleRecord.Every every = new ScheduleRecord.Every(
                ScheduleId.of("s1"), "tick", 300, "2026-08-26T11:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(new ScheduleChange.Create(every)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertInstanceOf(ScheduleRuntime.DueDecision.Every.class, decision);
        ScheduleRuntime.DueDecision.Every e = (ScheduleRuntime.DueDecision.Every) decision;
        assertEquals(1, e.reminders().size());
        assertNotNull(e.acceptedAt());
    }

    @Test
    void decideOneShotBeforeEveryWhenBothOverdue() {
        ScheduleRecord.Every every = new ScheduleRecord.Every(
                ScheduleId.of("s-every"), "tick", 300, "2026-08-26T11:00:00.000Z");
        ScheduleRecord.At oneShot = new ScheduleRecord.At(
                ScheduleId.of("s-at"), "wake", "2026-08-26T10:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(
                new ScheduleChange.Create(every),
                new ScheduleChange.Create(oneShot)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertInstanceOf(ScheduleRuntime.DueDecision.OneShot.class, decision);
        assertEquals("s-at", ((ScheduleRuntime.DueDecision.OneShot) decision).record().id().value());
    }

    @Test
    void renderFramingForOneShot() {
        ScheduleRecord.At past = new ScheduleRecord.At(
                ScheduleId.of("s1"), "wake", "2026-08-26T11:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(new ScheduleChange.Create(past)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        String framing = ScheduleRuntime.renderFraming(decision);
        assertNotNull(framing);
        assertTrue(framing.contains("[SCHEDULE REMINDER]"));
        assertTrue(framing.contains("wake"));
    }

    @Test
    void renderFramingForWaitIsNull() {
        ScheduleRuntime rt = new ScheduleRuntime(new SessionLog(SessionId.of("ses-empty")));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertNull(ScheduleRuntime.renderFraming(decision));
    }

    @Test
    void dispatchChangesForOneShotProducesDispatch() {
        ScheduleRecord.At past = new ScheduleRecord.At(
                ScheduleId.of("s1"), "wake", "2026-08-26T11:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(new ScheduleChange.Create(past)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        List<ScheduleChange> changes = ScheduleRuntime.dispatchChangesFor(decision);
        assertEquals(1, changes.size());
        assertInstanceOf(ScheduleChange.Dispatch.class, changes.get(0));
        assertNull(((ScheduleChange.Dispatch) changes.get(0)).acceptedAt());
    }

    @Test
    void dispatchChangesForEveryProducesBatchWithAcceptedAt() {
        ScheduleRecord.Every every = new ScheduleRecord.Every(
                ScheduleId.of("s1"), "tick", 300, "2026-08-26T11:00:00.000Z");
        ScheduleRuntime rt = new ScheduleRuntime(sessionWith(new ScheduleChange.Create(every)));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        List<ScheduleChange> changes = ScheduleRuntime.dispatchChangesFor(decision);
        assertEquals(1, changes.size());
        ScheduleChange.Dispatch d = (ScheduleChange.Dispatch) changes.get(0);
        assertNotNull(d.acceptedAt());
    }

    @Test
    void dispatchChangesForWaitIsEmpty() {
        ScheduleRuntime rt = new ScheduleRuntime(new SessionLog(SessionId.of("ses-empty")));
        ScheduleRuntime.DueDecision decision = rt.decide(NOW);
        assertTrue(ScheduleRuntime.dispatchChangesFor(decision).isEmpty());
    }

    @Test
    void readFoldedReflectsDispatch() {
        ScheduleRecord.At past = new ScheduleRecord.At(
                ScheduleId.of("s1"), "wake", "2026-08-26T11:00:00.000Z");
        SessionLog session = sessionWith(new ScheduleChange.Create(past));
        session.append(ScheduleChangeCodec.EVENT_TYPE,
                ScheduleChangeCodec.encode(ScheduleChange.Dispatch.oneShot(ScheduleId.of("s1"))));
        ScheduleRuntime rt = new ScheduleRuntime(session);
        assertTrue(rt.readFolded().active().isEmpty());
    }
}
