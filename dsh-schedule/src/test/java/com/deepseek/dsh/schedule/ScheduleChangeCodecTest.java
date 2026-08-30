package com.deepseek.dsh.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

class ScheduleChangeCodecTest {

    @Test
    void createChangeRoundTrips() {
        ScheduleRecord.After record = new ScheduleRecord.After(
                ScheduleId.of("schedule-1"), "hello", 60, "2026-08-26T12:01:00.000Z");
        ScheduleChange.Create change = new ScheduleChange.Create(record);
        Map<String, Object> payload = ScheduleChangeCodec.encode(change);
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
        Map<String, Object> payload = ScheduleChangeCodec.encode(change);
        ScheduleChange decoded = ScheduleChangeCodec.decode(payload);
        assertInstanceOf(ScheduleChange.Delete.class, decoded);
        assertEquals("schedule-1", ((ScheduleChange.Delete) decoded).id().value());
    }

    @Test
    void oneShotDispatchRoundTrips() {
        ScheduleChange.Dispatch change = ScheduleChange.Dispatch.oneShot(ScheduleId.of("schedule-1"));
        Map<String, Object> payload = ScheduleChangeCodec.encode(change);
        ScheduleChange decoded = ScheduleChangeCodec.decode(payload);
        assertInstanceOf(ScheduleChange.Dispatch.class, decoded);
        ScheduleChange.Dispatch d = (ScheduleChange.Dispatch) decoded;
        assertNull(d.acceptedAt());
    }

    @Test
    void everyDispatchRoundTrips() {
        ScheduleChange.Dispatch change = ScheduleChange.Dispatch.every(
                ScheduleId.of("schedule-1"), "2026-08-26T12:07:30.000Z");
        Map<String, Object> payload = ScheduleChangeCodec.encode(change);
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
        session.append(ScheduleChangeCodec.EVENT_TYPE,
                ScheduleChangeCodec.encode(new ScheduleChange.Create(record)));
        session.append(ScheduleChangeCodec.EVENT_TYPE,
                ScheduleChangeCodec.encode(ScheduleChange.Dispatch.oneShot(ScheduleId.of("schedule-1"))));

        List<ScheduleChange> changes = ScheduleChangeCodec.extract(session.snapshot());
        assertEquals(2, changes.size());
        assertInstanceOf(ScheduleChange.Create.class, changes.get(0));
        assertInstanceOf(ScheduleChange.Dispatch.class, changes.get(1));
    }

    @Test
    void extractIgnoresNonScheduleCommands() {
        SessionLog session = new SessionLog(SessionId.of("ses-1"));
        session.append("command", Map.of("feedback", "record", "text", "hi"));
        session.append("user/message", Map.of("content", List.of(Map.of("type", "text", "text", "hello"))));
        assertTrue(ScheduleChangeCodec.extract(session.snapshot()).isEmpty());
    }

    @Test
    void isScheduleChangeDetectsMarker() {
        SessionLog session = new SessionLog(SessionId.of("ses-1"));
        session.append(ScheduleChangeCodec.EVENT_TYPE,
                ScheduleChangeCodec.encode(new ScheduleChange.Delete(ScheduleId.of("s1"))));
        SessionEvent scheduleEvent = session.snapshot().get(0);

        session.append("command", Map.of("other", "x"));
        SessionEvent otherEvent = session.snapshot().get(1);

        assertTrue(ScheduleChangeCodec.isScheduleChange(scheduleEvent));
        assertFalse(ScheduleChangeCodec.isScheduleChange(otherEvent));
    }

    @Test
    void decodeRejectsBadVersion() {
        Map<String, Object> bad = new java.util.LinkedHashMap<>();
        bad.put(ScheduleChangeCodec.MARKER_KEY, ScheduleChangeCodec.MARKER_VALUE);
        bad.put("version", 2);
        bad.put("operation", "create");
        assertThrows(ScheduleException.class, () -> ScheduleChangeCodec.decode(bad));
    }

    @Test
    void decodeRejectsBadOperation() {
        Map<String, Object> bad = new java.util.LinkedHashMap<>();
        bad.put(ScheduleChangeCodec.MARKER_KEY, ScheduleChangeCodec.MARKER_VALUE);
        bad.put("version", 1);
        bad.put("operation", "nope");
        assertThrows(ScheduleException.class, () -> ScheduleChangeCodec.decode(bad));
    }

    @Test
    void decodeRejectsNull() {
        assertThrows(ScheduleException.class, () -> ScheduleChangeCodec.decode(null));
    }
}
