package com.deepseek.dsh.web.server;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RemoteMuxRegistry 单元测试 —— 验证 $events 和 session/follow 流注册、
 * emit 广播、follow 事件转发。
 */
class RemoteMuxRegistryTest {

    @Test
    void broadcastEmitNoStreamsIsNoOp() {
        RemoteMuxRegistry registry = new RemoteMuxRegistry();
        registry.broadcastEmit("session/event", new Object[]{Map.of("test", true)});
        // 无异常即通过
    }

    @Test
    void broadcastFollowEventNoStreamsIsNoOp() {
        RemoteMuxRegistry registry = new RemoteMuxRegistry();
        registry.broadcastFollowEvent("nonexistent-session", Map.of("type", "turn/start"));
        // 无异常即通过
    }

    @Test
    void buildFollowSnapshotWithoutProviderReturnsNull() {
        RemoteMuxRegistry registry = new RemoteMuxRegistry();
        assertNull(registry.buildFollowSnapshot("any-session"));
    }

    @Test
    void buildFollowSnapshotWithProviderReturnsSnapshot() {
        RemoteMuxRegistry registry = new RemoteMuxRegistry();
        registry.setSnapshotProvider(sid -> new RemoteMuxRegistry.FollowSnapshot(
                List.of(Map.of("type", "event")), 5L, false,
                Map.of("asOfSeq", 5L, "values", Map.of("title", "test")),
                Map.of("version", 0, "id", sid)));
        var snapshot = registry.buildFollowSnapshot("test-session");
        assertNotNull(snapshot);
        assertEquals(5L, snapshot.cursor());
        assertEquals(1, snapshot.records().size());
    }

    @Test
    void followSnapshotRecordShape() {
        var snapshot = new RemoteMuxRegistry.FollowSnapshot(
                List.of(Map.of("type", "chunks")), 10L, true,
                Map.of("asOfSeq", 10L), Map.of("version", 0, "id", "test"));
        assertEquals(10L, snapshot.cursor());
        assertTrue(snapshot.hasMore());
        assertEquals(1, snapshot.records().size());
    }
}
