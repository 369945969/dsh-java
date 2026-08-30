package com.deepseek.dsh.session.log;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;

import static org.junit.jupiter.api.Assertions.*;

class SessionLogTest {

    @Test
    void appendAndRead() {
        var log = new SessionLog(SessionId.of("s1"));
        assertEquals(-1, log.lastSeq());
        assertEquals(0, log.size());
        log.append("turn/start", Map.of("turn", 0));
        log.append("user/message", Map.of(
                "content", List.of(Map.of("type", "text", "text", "hi")),
                "source", Map.of("kind", "user"), "role", "user", "id", "u-1"), "append");
        log.append("assistant/message", Map.of(
                "message", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "hello")),
                        "source", Map.of("kind", "model"))), "append");
        assertEquals(3, log.size());
        assertEquals(2, log.lastSeq());
        assertEquals(3, log.snapshot().size());
    }

    @Test
    void deriveMessages() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append("user/message", Map.of(
                "content", List.of(Map.of("type", "text", "text", "Hello")),
                "source", Map.of("kind", "user"), "role", "user", "id", "u-1"), "append");
        log.append("assistant/message", Map.of(
                "message", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "Hello!")),
                        "source", Map.of("kind", "model"))), "append");
        var proj = log.deriveMessages();
        assertEquals(2, proj.messages().size());
        assertEquals(ChatMessage.Role.USER, proj.messages().get(0).role());
        assertEquals("Hello", proj.messages().get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, proj.messages().get(1).role());
    }

    @Test
    void toolCallRoundTrip() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append("user/message", Map.of(
                "content", List.of(Map.of("type", "text", "text", "List files")),
                "source", Map.of("kind", "user"), "role", "user", "id", "u-1"), "append");
        log.append("tool/call", Map.of("callId", "call-1", "name", "bash", "arguments", "{\"cmd\":\"ls\"}"));
        log.append("tool/result", Map.of(
                "message", Map.of(
                        "source", Map.of("callId", "call-1"),
                        "content", List.of(Map.of("type", "text", "text", "file1\nfile2")))));
        var proj = log.deriveMessages();
        assertTrue(proj.messages().size() >= 2);
    }

    @Test
    void controlEventsNotProjected() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append("turn/start", Map.of("turn", 0));
        log.append("step/start", Map.of("turn", 0, "step", 0));
        log.append("user/message", Map.of(
                "content", List.of(Map.of("type", "text", "text", "hi")),
                "source", Map.of("kind", "user"), "role", "user", "id", "u-1"), "append");
        log.append("turn/end", Map.of("turn", 0, "reason", Map.of("kind", "complete")));
        assertEquals(1, log.deriveMessages().messages().size());
    }
}
