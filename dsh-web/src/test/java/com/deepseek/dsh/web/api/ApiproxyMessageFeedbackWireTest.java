package com.deepseek.dsh.web.api;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.feedback.FeedbackException;
import com.deepseek.dsh.feedback.FeedbackRating;
import com.deepseek.dsh.feedback.MessageFeedbackItem;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 messageFeedback RPC 的 wire 信封：业务结果整体嵌套在载体 {@code value} 里
 * （对应 TS 端 {@code MessageFeedback{List|Put|Delete}Result}），且版本冲突携带
 * 权威 {@code current} 供前端就地协商。
 */
class ApiproxyMessageFeedbackWireTest {

    @Test
    void feedbackOkNestsBusinessResultInsideCarrierValue() throws Exception {
        var ok = ApiproxyController.class.getDeclaredMethod("ok", Object.class);
        ok.setAccessible(true);
        var response = ApiproxyController.class.getDeclaredMethod("response", String.class, Map.class);
        response.setAccessible(true);
        var feedbackOk = ApiproxyController.class.getDeclaredMethod("feedbackOk", String.class, Map.class);
        feedbackOk.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> business = (Map<String, Object>) ok.invoke(null, Map.of("absent", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) feedbackOk.invoke(null, "rpc-1", business);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertEquals(true, result.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.get("value");
        assertEquals(true, value.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) value.get("value");
        assertEquals(true, inner.get("absent"));
    }

    @Test
    void feedbackRejectCarriesVersionConflictCurrent() throws Exception {
        var feedbackItem = ApiproxyController.class.getDeclaredMethod("feedbackItem", MessageFeedbackItem.class);
        feedbackItem.setAccessible(true);
        var feedbackReject = ApiproxyController.class.getDeclaredMethod("feedbackReject", FeedbackException.class);
        feedbackReject.setAccessible(true);

        MessageFeedbackItem current = new MessageFeedbackItem(
                "msg-1", FeedbackRating.POSITIVE, "note", "v-1", 1L, 1L);
        FeedbackException conflict = new FeedbackException(
                FeedbackException.Code.VERSION_CONFLICT, "conflict", current);

        @SuppressWarnings("unchecked")
        Map<String, Object> business = (Map<String, Object>) feedbackReject.invoke(null, conflict);
        assertEquals(false, business.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) business.get("error");
        assertEquals("version-conflict", error.get("code"));
        assertTrue(error.containsKey("current"));
        assertEquals("msg-1", ((Map<?, ?>) error.get("current")).get("messageId"));
    }

    @Test
    void feedbackRejectOmitsCurrentForNonConflict() throws Exception {
        var feedbackReject = ApiproxyController.class.getDeclaredMethod("feedbackReject", FeedbackException.class);
        feedbackReject.setAccessible(true);

        FeedbackException blank = new FeedbackException(
                FeedbackException.Code.NOTE_BLANK, "blank note");
        @SuppressWarnings("unchecked")
        Map<String, Object> business = (Map<String, Object>) feedbackReject.invoke(null, blank);
        assertFalse(business.containsKey("value"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) business.get("error");
        assertEquals("note-blank", error.get("code"));
        assertFalse(error.containsKey("current"));
    }
}
