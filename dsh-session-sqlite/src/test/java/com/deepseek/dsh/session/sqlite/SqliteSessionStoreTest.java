package com.deepseek.dsh.session.sqlite;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 会话持久化 + FTS 检索测试。
 */
class SqliteSessionStoreTest {

    @Test
    void 追加并加载事件() throws Exception {
        var db = Files.createTempFile("dsh-test", ".db");
        try (var store = new SqliteSessionStore(db.toString())) {
            SessionId id = SessionId.of("sess-1");
            store.append(new SessionEvent(0, id, SessionEvent.Type.USER_MESSAGE,
                    SessionEvent.Payload.text("hello world"),
                    java.time.Instant.now(), SessionEvent.Lineage.root()));
            store.append(new SessionEvent(1, id, SessionEvent.Type.ASSISTANT_MESSAGE,
                    SessionEvent.Payload.text("hi there"),
                    java.time.Instant.now(), SessionEvent.Lineage.root()));

            var loaded = store.load(id);
            assertEquals(2, loaded.size());
            assertEquals("hello world", loaded.get(0).payload().text());
        }
    }

    @Test
    void FTS全文检索() throws Exception {
        var db = Files.createTempFile("dsh-fts", ".db");
        try (var store = new SqliteSessionStore(db.toString())) {
            SessionId id = SessionId.of("sess-2");
            store.append(new SessionEvent(0, id, SessionEvent.Type.USER_MESSAGE,
                    SessionEvent.Payload.text("排查 timeout 错误"),
                    java.time.Instant.now(), SessionEvent.Lineage.root()));
            store.append(new SessionEvent(1, id, SessionEvent.Type.ASSISTANT_MESSAGE,
                    SessionEvent.Payload.text("已修复错误"),
                    java.time.Instant.now(), SessionEvent.Lineage.root()));

            var hits = store.search("timeout", 10);
            assertFalse(hits.isEmpty());
            assertTrue(hits.get(0).text().contains("timeout"));
        }
    }
}
