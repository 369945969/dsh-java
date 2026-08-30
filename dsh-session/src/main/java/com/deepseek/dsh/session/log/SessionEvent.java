package com.deepseek.dsh.session.log;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 会话事件 —— 不可变的、仅追加的日志记录，存储 wire 格式（与浏览器收到的格式一致）。
 *
 * <p>对应 harness 的 SessionEvent：每个事件都有唯一递增 seq，
 * 与 live event 和 snapshot cursor 完全一致，无需映射。
 *
 * <p>设计模式：事件溯源（Event Sourcing）中的领域事件。
 */
public record SessionEvent(
        long seq,
        SessionId sessionId,
        String type,
        Map<String, Object> data,
        long time,
        String surfaceOp,
        Lineage lineage
) {

    public SessionEvent {
        if (surfaceOp == null) surfaceOp = "";
        if (lineage == null) lineage = Lineage.root();
    }

    public SessionEvent(long seq, SessionId sessionId, String type, Map<String, Object> data, long time) {
        this(seq, sessionId, type, data, time, null, Lineage.root());
    }

    public SessionEvent(long seq, SessionId sessionId, String type, Map<String, Object> data, long time, String surfaceOp) {
        this(seq, sessionId, type, data, time, surfaceOp, Lineage.root());
    }

    public record Lineage(SessionId parentSession, int delegationDepth) {
        public static Lineage root() { return new Lineage(null, 0); }
    }

    public record Projection(List<ChatMessage> messages, long lastSeq) {}
}
