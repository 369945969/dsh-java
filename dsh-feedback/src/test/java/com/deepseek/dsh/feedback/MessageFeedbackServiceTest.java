package com.deepseek.dsh.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 消息反馈侧车服务测试 —— 覆盖 list/put/delete 的乐观并发与备注校验。
 */
class MessageFeedbackServiceTest {

    private static SessionId sid() {
        return SessionId.of("ses-1");
    }

    @Test
    void putThenListRoundTrips() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        MessageFeedbackItem item = svc.put(s, "msg-1", FeedbackRating.POSITIVE, "great", null);
        assertEquals(FeedbackRating.POSITIVE, item.rating());
        assertEquals("great", item.note());
        assertEquals(1, svc.list(s).size());
        assertEquals(item, svc.list(s).get(0));
    }

    @Test
    void putNullNoteOmitted() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        MessageFeedbackItem item = svc.put(s, "msg-1", FeedbackRating.NEGATIVE, null, null);
        assertNull(item.note());
    }

    @Test
    void putRequiresVersionMatchForExisting() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        MessageFeedbackItem first = svc.put(s, "msg-1", FeedbackRating.POSITIVE, null, null);
        FeedbackException ex = assertThrows(FeedbackException.class,
                () -> svc.put(s, "msg-1", FeedbackRating.NEGATIVE, null, "stale-version"));
        assertEquals(FeedbackException.Code.VERSION_CONFLICT, ex.code());
        assertEquals(first, ex.conflictingCurrent());
    }

    @Test
    void putWithCorrectVersionReplaces() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        MessageFeedbackItem first = svc.put(s, "msg-1", FeedbackRating.POSITIVE, null, null);
        MessageFeedbackItem second = svc.put(s, "msg-1", FeedbackRating.NEGATIVE, "bad", first.version());
        assertEquals(FeedbackRating.NEGATIVE, second.rating());
        assertNotEquals(first.version(), second.version());
        assertEquals(first.createdAt(), second.createdAt());
    }

    @Test
    void putNoOpReturnsExistingVersion() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        MessageFeedbackItem first = svc.put(s, "msg-1", FeedbackRating.POSITIVE, "note", null);
        MessageFeedbackItem noOp = svc.put(s, "msg-1", FeedbackRating.POSITIVE, "note", first.version());
        assertEquals(first.version(), noOp.version());
    }

    @Test
    void putRejectsBlankNote() {
        MessageFeedbackService svc = new MessageFeedbackService();
        FeedbackException ex = assertThrows(FeedbackException.class,
                () -> svc.put(sid(), "msg-1", FeedbackRating.POSITIVE, "   ", null));
        assertEquals(FeedbackException.Code.NOTE_BLANK, ex.code());
    }

    @Test
    void putRejectsOversizedNote() {
        MessageFeedbackService svc = new MessageFeedbackService(null, 4, null);
        FeedbackException ex = assertThrows(FeedbackException.class,
                () -> svc.put(sid(), "msg-1", FeedbackRating.POSITIVE, "abcdefgh", null));
        assertEquals(FeedbackException.Code.NOTE_TOO_LARGE, ex.code());
    }

    @Test
    void deleteAbsentIsIdempotent() {
        MessageFeedbackService svc = new MessageFeedbackService();
        assertTrue(svc.delete(sid(), "msg-x", "any"));
    }

    @Test
    void deleteRequiresVersionMatch() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        svc.put(s, "msg-1", FeedbackRating.POSITIVE, null, null);
        FeedbackException ex = assertThrows(FeedbackException.class,
                () -> svc.delete(s, "msg-1", "stale"));
        assertEquals(FeedbackException.Code.VERSION_CONFLICT, ex.code());
    }

    @Test
    void deleteWithCorrectVersionRemoves() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        MessageFeedbackItem first = svc.put(s, "msg-1", FeedbackRating.POSITIVE, null, null);
        assertTrue(svc.delete(s, "msg-1", first.version()));
        assertTrue(svc.list(s).isEmpty());
    }

    @Test
    void multipleItemsPerSession() {
        MessageFeedbackService svc = new MessageFeedbackService();
        SessionId s = sid();
        svc.put(s, "msg-1", FeedbackRating.POSITIVE, null, null);
        svc.put(s, "msg-2", FeedbackRating.NEGATIVE, "nope", null);
        assertEquals(2, svc.list(s).size());
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        SessionId s = sid();
        MessageFeedbackService first = new MessageFeedbackService(dir, 8192, null);
        first.put(s, "msg-1", FeedbackRating.POSITIVE, "note", null);
        first.dispose();

        MessageFeedbackService second = new MessageFeedbackService(dir, 8192, null);
        assertEquals(1, second.list(s).size());
        assertEquals(FeedbackRating.POSITIVE, second.list(s).get(0).rating());
    }

    @Test
    void sessionNotFoundWhenSessionsRejects() {
        com.deepseek.dsh.session.Sessions rejecting = new com.deepseek.dsh.session.Sessions() {
            @Override public com.deepseek.dsh.session.log.SessionLog create() { return null; }
            @Override public java.util.Optional<com.deepseek.dsh.session.log.SessionLog> get(SessionId id) {
                return java.util.Optional.empty();
            }
            @Override public com.deepseek.dsh.session.log.SessionLog getOrCreate(SessionId id) { return null; }
            @Override public void persist(com.deepseek.dsh.session.log.SessionEvent event) { }
            @Override public java.util.List<SessionId> list() { return java.util.List.of(); }
        };
        MessageFeedbackService svc = new MessageFeedbackService(null, 8192, rejecting);
        FeedbackException ex = assertThrows(FeedbackException.class,
                () -> svc.put(sid(), "msg-1", FeedbackRating.POSITIVE, null, null));
        assertEquals(FeedbackException.Code.SESSION_NOT_FOUND, ex.code());
    }
}
