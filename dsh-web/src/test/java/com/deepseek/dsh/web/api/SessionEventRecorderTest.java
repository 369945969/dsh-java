package com.deepseek.dsh.web.api;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.SessionManager;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.persistence.JsonlSessionStore;
import com.deepseek.dsh.session.persistence.SessionStore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 非 apiproxy 对话端点（AgentStreamController / AgentController）经 {@link SessionEventRecorder}
 * 把对话写入 SessionLog 并持久化 —— 刷新后历史（从日志投影）应可恢复。
 */
class SessionEventRecorderTest {

    @TempDir
    java.nio.file.Path tmp;

    @Test
    void recordsUserAndAssistantToDurableLog() throws Exception {
        Files.createDirectories(tmp);
        SessionStore store = new JsonlSessionStore(tmp);
        SessionManager mgr = new SessionManager(store);
        Context ctx = Context.root();
        ctx.register(Sessions.class, mgr);

        SessionEventRecorder recorder = new SessionEventRecorder();
        String sid = "test-session-1";

        recorder.record(ctx, sid, "user/message",
                Map.of("id", "u-1", "content", "你好", "source", "browser"));
        recorder.record(ctx, sid, "assistant/message",
                Map.of("message", Map.of("id", "a-1", "content", "你好！"),
                        "turn", 1, "step", 0));

        // 同进程（浏览器刷新）：事件已入日志
        assertEquals(2, mgr.getOrCreate(SessionId.of(sid)).events().size());

        // 后端重启：新管理器 + 同一 store，重放后历史仍完整
        SessionManager reloaded = new SessionManager(store);
        assertEquals(2, reloaded.getOrCreate(SessionId.of(sid)).events().size());
    }

    @Test
    void eventsAreSeqOrderedAfterConcurrentAppends() throws Exception {
        Files.createDirectories(tmp);
        SessionStore store = new JsonlSessionStore(tmp);
        SessionManager mgr = new SessionManager(store);
        Context ctx = Context.root();
        ctx.register(Sessions.class, mgr);
        SessionEventRecorder recorder = new SessionEventRecorder();
        String sid = "concurrent-test";

        // 并发追加 20 条事件（模拟并行工具调用）
        Thread[] threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            threads[i] = Thread.startVirtualThread(() ->
                recorder.record(ctx, sid, "assistant/chunk",
                        Map.of("chunk", Map.of("type", "text-delta", "text", "t" + idx))));
        }
        for (Thread t : threads) t.join();

        // 验证 seq 严格递增（sendSessionEvent synchronized 保证顺序）
        var events = mgr.getOrCreate(SessionId.of(sid)).events();
        assertEquals(20, events.size());
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).seq() > events.get(i - 1).seq(),
                    "seq should be monotonic: " + events.get(i - 1).seq() + " -> " + events.get(i).seq());
        }
    }

    @Test
    void pluginInjectionMessagesExcludedFromTurnCount() throws Exception {
        Files.createDirectories(tmp);
        SessionStore store = new JsonlSessionStore(tmp);
        SessionManager mgr = new SessionManager(store);
        Context ctx = Context.root();
        ctx.register(Sessions.class, mgr);
        SessionEventRecorder recorder = new SessionEventRecorder();
        String sid = "turn-count-test";

        // 模拟 turn 0 的注入消息 + 真实用户消息
        recorder.record(ctx, sid, "user/message", Map.of(
                "id", "u-ctx", "content", "runtime context",
                "source", Map.of("kind", "plugin", "plugin", "dsh-system-prompt", "form", "snapshot")));
        recorder.record(ctx, sid, "user/message", Map.of(
                "id", "u-skill", "content", "skill reminder",
                "source", Map.of("kind", "plugin", "plugin", "dsh-skill", "form", "system-reminder")));
        recorder.record(ctx, sid, "user/message", Map.of(
                "id", "u-real", "content", "你好",
                "source", Map.of("kind", "user")));

        // nextTurn 应只计 1 条真实用户消息（跳过 2 条 plugin 注入）
        // 验证：日志中有 3 条 user/message，但只有 1 条 source.kind=user
        var events = mgr.getOrCreate(SessionId.of(sid)).events();
        long realUserCount = events.stream()
                .filter(e -> "user/message".equals(e.type()))
                .filter(e -> !(e.data().get("source") instanceof Map<?, ?> src
                        && "plugin".equals(src.get("kind"))))
                .count();
        assertEquals(1, realUserCount, "only 1 real user message, not 3");
    }
}
