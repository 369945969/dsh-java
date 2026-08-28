package com.deepseek.dsh.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.interaction.command.CommandRegistry.CommandHandler;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * /feedback 命令测试 —— 覆盖记录、回执与 COMMAND 事件负载编码。
 */
class FeedbackCommandTest {

    private static SessionLog newSession() {
        return new SessionLog(SessionId.of("ses-cmd"));
    }

    @Test
    void recordsTrimmedFeedbackEvent() {
        SessionLog session = newSession();
        FeedbackCommand.recordFeedback(session, "  hello world  ");
        List<SessionEvent> events = session.snapshot();
        assertEquals(1, events.size());
        SessionEvent e = events.get(0);
        assertEquals(SessionEvent.Type.COMMAND, e.type());
        assertEquals("hello world", FeedbackRecord.decode(e.payload()));
    }

    @Test
    void recordRejectsEmpty() {
        SessionLog session = newSession();
        assertThrows(IllegalArgumentException.class, () -> FeedbackCommand.recordFeedback(session, "   "));
        assertThrows(IllegalArgumentException.class, () -> FeedbackCommand.recordFeedback(session, ""));
        assertThrows(IllegalArgumentException.class, () -> FeedbackCommand.recordFeedback(session, null));
        assertTrue(session.snapshot().isEmpty());
    }

    @Test
    void executeReturnsUsageWhenNoText() {
        SessionLog session = newSession();
        String ack = FeedbackCommand.execute(session, "");
        assertTrue(ack.contains("Feedback text is required"));
        assertTrue(session.snapshot().isEmpty());
    }

    @Test
    void executeRecordsAndAcknowledges() {
        SessionLog session = newSession();
        String ack = FeedbackCommand.execute(session, "nice work");
        assertTrue(ack.contains("Feedback recorded for session " + session.sessionId().value()));
        assertEquals(1, session.snapshot().size());
    }

    @Test
    void handlerBindsToSession() {
        SessionLog session = newSession();
        CommandHandler handler = FeedbackCommand.handler(() -> session);
        String ack = handler.handle(new String[]{"some", "feedback"});
        assertTrue(ack.contains("Feedback recorded"));
        assertEquals(1, session.snapshot().size());
        assertEquals("some feedback", FeedbackRecord.decode(session.snapshot().get(0).payload()));
    }

    @Test
    void handlerHandlesNoSession() {
        CommandHandler handler = FeedbackCommand.handler(() -> null);
        String ack = handler.handle(new String[]{"x"});
        assertTrue(ack.contains("No active session"));
    }

    @Test
    void handlerHandlesEmptyArgs() {
        SessionLog session = newSession();
        CommandHandler handler = FeedbackCommand.handler(() -> session);
        String ack = handler.handle(new String[0]);
        assertTrue(ack.contains("Feedback text is required"));
        assertTrue(session.snapshot().isEmpty());
    }

    @Test
    void feedbackRecordDecodeIgnoresNonFeedbackCommands() {
        SessionLog session = newSession();
        session.append(SessionEvent.Type.COMMAND,
                new SessionEvent.Payload(null, java.util.Map.of("feedback", "other"), null, null, null));
        assertNull(FeedbackRecord.decode(session.snapshot().get(0).payload()));
    }

    @Test
    void feedbackRecordDecodeNullPayload() {
        assertNull(FeedbackRecord.decode(null));
        assertNull(FeedbackRecord.decode(new SessionEvent.Payload(null, java.util.Map.of(), null, null, null)));
    }

    @Test
    void ratingRoundTrips() {
        assertEquals(FeedbackRating.POSITIVE, FeedbackRating.of("positive"));
        assertEquals(FeedbackRating.NEGATIVE, FeedbackRating.of("negative"));
        assertEquals("positive", FeedbackRating.POSITIVE.wire());
        assertThrows(IllegalArgumentException.class, () -> FeedbackRating.of("maybe"));
        assertThrows(IllegalArgumentException.class, () -> FeedbackRating.of(null));
    }
}
