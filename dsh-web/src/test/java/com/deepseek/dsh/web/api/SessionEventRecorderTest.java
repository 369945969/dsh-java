package com.deepseek.dsh.web.api;

import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.SessionManager;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.persistence.JsonlSessionStore;
import com.deepseek.dsh.session.persistence.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
