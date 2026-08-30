package com.deepseek.dsh.session.sqlite;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;

import static org.junit.jupiter.api.Assertions.*;

class SqliteSessionStoreTest {

    @Test
    void appendAndLoad() throws Exception {
        var db = Files.createTempFile("dsh-test", ".db");
        try (var store = new SqliteSessionStore(db)) {
            SessionId id = SessionId.of("sess-1");
            store.append(new SessionEvent(0, id, "user/message",
                    Map.of("content", List.of(Map.of("type", "text", "text", "hello world")),
                            "source", Map.of("kind", "user"), "role", "user", "id", "u-1"),
                    System.currentTimeMillis(), "append"));
            store.append(new SessionEvent(1, id, "assistant/message",
                    Map.of("message", Map.of(
                            "content", List.of(Map.of("type", "text", "text", "hi there")),
                            "source", Map.of("kind", "model"))),
                    System.currentTimeMillis(), "append"));

            var loaded = store.load(id);
            assertEquals(2, loaded.size());
            assertEquals("user/message", loaded.get(0).type());
            assertEquals("assistant/message", loaded.get(1).type());
        }
    }
}
