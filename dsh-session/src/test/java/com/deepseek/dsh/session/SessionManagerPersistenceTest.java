package com.deepseek.dsh.session;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.session.persistence.JsonlSessionStore;
import com.deepseek.dsh.session.persistence.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 会话创建后，事件同时落内存与磁盘；以 ci 文件系统为后端重启（新建 manager 重放）后，
 * {@code session.history} 所读取的 events() 应完整返回 —— 浏览器刷新（同进程）与后端重启两种场景都成立。
 */
class SessionManagerPersistenceTest {

    @TempDir
    java.nio.file.Path tmp;

    @Test
    void createdSessionEventsSurviveReload() throws Exception {
        Files.createDirectories(tmp);
        SessionStore store = new JsonlSessionStore(tmp);
        SessionManager mgr = new SessionManager(store);
        SessionLog slog = mgr.create();
        SessionId sid = slog.sessionId();

        SessionEvent a = slog.append("user/message", Map.of(
                "content", List.of(Map.of("type", "text", "text", "你好")),
                "source", Map.of("kind", "user"), "role", "user", "id", "u-1"), "append");
        SessionEvent b = slog.append("assistant/message", Map.of(
                "message", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "你好！")),
                        "source", Map.of("kind", "model"))), "append");
        mgr.persist(a);
        mgr.persist(b);

        // 同进程（浏览器刷新）：内存 active 仍在，events() 直接返回
        assertEquals(2, mgr.get(sid).orElseThrow().events().size());

        // 后端重启：新管理器 + 同一 store，getOrCreate 重放磁盘
        SessionManager reloaded = new SessionManager(store);
        SessionLog replayed = reloaded.getOrCreate(sid);
        assertEquals(2, replayed.events().size(), "磁盘重放后历史事件应完整");
        assertEquals("user/message", replayed.events().get(0).type());
        assertEquals("assistant/message", replayed.events().get(1).type());
    }
}
