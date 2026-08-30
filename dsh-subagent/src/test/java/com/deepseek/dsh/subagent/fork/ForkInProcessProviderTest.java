package com.deepseek.dsh.subagent.fork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.SessionManager;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.session.persistence.SessionStore;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentEvent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程内 fork 提供者 + 子 agent 生命周期事件转发测试。
 */
class ForkInProcessProviderTest {

    /** 记录两事件并返回回复的假 agent。 */
    static final class FakeAgent implements Agent {
        @Override public String name() { return "fake-worker"; }
        @Override public String systemPrompt() { return "你是子 agent"; }
        @Override
        public String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) {
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog log = sessions.getOrCreate(sessionId);
            log.append("user/message", java.util.Map.of("content", java.util.List.of(java.util.Map.of("type", "text", "text", userMessage)), "source", java.util.Map.of("kind", "user"), "role", "user", "id", "u-1"), "append");
            log.append("assistant/message", java.util.Map.of("message", java.util.Map.of("content", java.util.List.of(java.util.Map.of("type", "text", "text", "Subtask completed")), "source", java.util.Map.of("kind", "model"))), "append");
            return "Subtask completed";
        }
    }

    /** 全内存 SessionStore（无 IO）。 */
    private static SessionStore memStore() {
        return new SessionStore() {
            private final Map<SessionId, List<SessionEvent>> db = new ConcurrentHashMap<>();
            @Override public void append(SessionEvent event) { db.computeIfAbsent(event.sessionId(), k -> new CopyOnWriteArrayList<>()).add(event); }
            @Override public List<SessionEvent> load(SessionId sessionId) { return db.getOrDefault(sessionId, List.of()); }
            @Override public List<SessionId> listAll() { return new ArrayList<>(db.keySet()); }
        };
    }

    @Test
    void 委派发射Spawned与Completed生命周期事件() {
        Context ctx = Context.root();
        new SessionManager(memStore()).apply(ctx);

        List<SubagentEvent> events = new CopyOnWriteArrayList<>();
        ctx.events().on(SubagentEvent.class, (e, next) -> { events.add(e); return next.invoke(e); });

        var provider = new ForkInProcessProvider();
        DelegationResult result = provider.delegate(
                SessionId.of("parent"), ScopeKey.random(), ctx, new FakeAgent(), "做点事");

        assertTrue(result.success());
        assertNotNull(result.childSessionId());
        // 假 agent 记录了 2 条事件 → 转发事件数 = 2
        assertEquals(2, result.forwardedEventCount());
        // 收到 Spawned + Completed 两个生命周期事件
        assertEquals(2, events.size());
        assertEquals(SubagentEvent.Kind.SPAWNED, events.get(0).kind());
        assertEquals(SubagentEvent.Kind.COMPLETED, events.get(1).kind());

        SubagentEvent spawned = events.get(0);
        SubagentEvent completed = events.get(1);
        assertEquals("fake-worker", spawned.persona());
        assertEquals(result.childSessionId(), spawned.childSessionId());
        assertEquals("parent", spawned.parentSessionId().value());
        assertEquals(result.childSessionId(), completed.childSessionId());
        assertEquals(2, completed.forwardedEventCount());
    }

    @Test
    void agent抛异常时发射Failed事件() {
        Context ctx = Context.root();
        new SessionManager(memStore()).apply(ctx);

        List<SubagentEvent> events = new CopyOnWriteArrayList<>();
        ctx.events().on(SubagentEvent.class, (e, next) -> { events.add(e); return next.invoke(e); });

        Agent failing = new Agent() {
            @Override public String name() { return "boom"; }
            @Override public String systemPrompt() { return ""; }
            @Override
            public String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) {
                throw new RuntimeException("Boom");
            }
        };

        var provider = new ForkInProcessProvider();
        DelegationResult result = provider.delegate(
                SessionId.of("parent"), ScopeKey.random(), ctx, failing, "做点事");

        assertFalse(result.success());
        // Spawned + Failed
        assertEquals(2, events.size());
        assertEquals(SubagentEvent.Kind.SPAWNED, events.get(0).kind());
        assertEquals(SubagentEvent.Kind.FAILED, events.get(1).kind());
        SubagentEvent failed = events.get(1);
        assertEquals("boom", failed.persona());
        assertTrue(failed.detail().contains("Boom"));
    }
}
