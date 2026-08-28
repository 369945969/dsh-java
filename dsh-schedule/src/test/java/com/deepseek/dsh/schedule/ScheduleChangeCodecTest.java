package com.deepseek.dsh.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 调度变更编解码器测试 —— 覆盖编码/解码往返、从会话事件提取、标识判断。
 */
class ScheduleChangeCodecTest {

    @Test
    void createChangeRoundTrips() {
        ScheduleRecord.After record = new ScheduleRecord.After(
                ScheduleId.of("schedule-1"), "hello", 60, "2026-08-26T12:01:00.000Z");
        ScheduleChange.Create change = new ScheduleChange.Create(record);
        SessionEvent.Payload payload = ScheduleChangeCodec.encode(change);
        ScheduleChange decoded = ScheduleChangeCodec.decode(payload);
        assertInstanceOf(ScheduleChange.Create.class, decoded);
        ScheduleChange.Create c = (ScheduleChange.Create) decoded;
        assertInstanceOf(ScheduleRecord.After.class, c.schedule());
        ScheduleRecord.After a = (ScheduleRecord.After) c.schedule();
        assertEquals("schedule-1", a.id().value());
        assertEquals("hello", a.prompt());
        assertEquals(60, a.afterSeconds());
        assertEquals("2026-08-26T12:01:00.000Z", a.scheduledAt());
    }

    @Test
    void deleteChangeRoundTrips() {
        ScheduleChange.Delete change = new ScheduleChange.Delete(ScheduleId.of("schedule-1"));
        SessionEvent.Payload payload = ScheduleChangeCodec.encode(change);
        ScheduleChange decoded = ScheduleChangeCodec.decode(payload);
        assertInstanceOf(ScheduleChange.Delete.class, decoded);
        assertEquals("schedule-1", ((ScheduleChange.Delete) decoded).id().value());
    }

    @Test
    void oneShotDispatchRoundTrips() {
        ScheduleChange.Dispatch change = ScheduleChange.Dispatch.oneShot(ScheduleId.of("schedule-1"));
        SessionEvent.Payload payload = ScheduleChangeCodec.encode(change);
        ScheduleChange decoded = ScheduleChangeCodec.decode(payload);
        assertInstanceOf(ScheduleChange.Dispatch.class, decoded);
        ScheduleChange.Dispatch d = (ScheduleChange.Dispatch) decoded;
        assertNull(d.acceptedAt());
    }

    @Test
    void everyDispatchRoundTrips() {
        ScheduleChange.Dispatch change = ScheduleChange.Dispatch.every(
                ScheduleId.of("schedule-1"), "2026-08-26T12:07:30.000Z");
        SessionEvent.Payload payload = ScheduleChangeCodec.encode(change);
        ScheduleChange decoded = ScheduleChangeCodec.decode(payload);
        assertInstanceOf(ScheduleChange.Dispatch.class, decoded);
        ScheduleChange.Dispatch d = (ScheduleChange.Dispatch) decoded;
        assertEquals("2026-08-26T12:07:30.000Z", d.acceptedAt());
    }

    @Test
    void extractFromSessionEvents() {
        SessionLog session = new SessionLog(SessionId.of("ses-1"));
        ScheduleRecord.After record = new ScheduleRecord.After(
                ScheduleId.of("schedule-1"), "hello", 60, "2026-08-26T12:01:00.000Z");
        session.append(SessionEvent.Type.COMMAND,
                ScheduleChangeCodec.encode(new ScheduleChange.Create(record)));
        session.append(SessionEvent.Type.COMMAND,
                ScheduleChangeCodec.encode(ScheduleChange.Dispatch.oneShot(ScheduleId.of("schedule-1"))));

        List<ScheduleChange> changes = ScheduleChangeCodec.extract(session.snapshot());
        assertEquals(2, changes.size());
        assertInstanceOf(ScheduleChange.Create.class, changes.get(0));
        assertInstanceOf(ScheduleChange.Dispatch.class, changes.get(1));
    }

    @Test
    void extractIgnoresNonScheduleCommands() {
        SessionLog session = new SessionLog(SessionId.of("ses-1"));
        session.append(SessionEvent.Type.COMMAND,
                new SessionEvent.Payload(null, java.util.Map.of("feedback", "record", "text", "hi"), null, null, null));
        session.append(SessionEvent.Type.USER_MESSAGE, SessionEvent.Payload.text("hello"));
        assertTrue(ScheduleChangeCodec.extract(session.snapshot()).isEmpty());
    }

    @Test
    void isScheduleChangeDetectsMarker() {
        SessionLog session = new SessionLog(SessionId.of("ses-1"));
        session.append(SessionEvent.Type.COMMAND,
                ScheduleChangeCodec.encode(new ScheduleChange.Delete(ScheduleId.of("s1"))));
        SessionEvent scheduleEvent = session.snapshot().get(0);

        session.append(SessionEvent.Type.COMMAND,
                new SessionEvent.Payload(null, java.util.Map.of("other", "x"), null, null, null));
        SessionEvent otherEvent = session.snapshot().get(1);

        assertTrue(ScheduleChangeCodec.isScheduleChange(scheduleEvent));
        assertFalse(ScheduleChangeCodec.isScheduleChange(otherEvent));
    }

    @Test
    void decodeRejectsBadVersion() {
        SessionEvent.Payload bad = new SessionEvent.Payload(null,
                java.util.Map.of("schedule", "change", "version", 2, "operation", "create"), null, null, null);
        assertThrows(ScheduleException.class, () -> ScheduleChangeCodec.decode(bad));
    }

    @Test
    void decodeRejectsBadOperation() {
        SessionEvent.Payload bad = new SessionEvent.Payload(null,
                java.util.Map.of("schedule", "change", "version", 1, "operation", "nope"), null, null, null);
        assertThrows(ScheduleException.class, () -> ScheduleChangeCodec.decode(bad));
    }

    @Test
    void decodeRejectsNonPayload() {
        assertThrows(ScheduleException.class, () -> ScheduleChangeCodec.decode(null));
    }
}
