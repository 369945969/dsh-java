package com.deepseek.dsh.session.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.session.log.SessionEvent;

/**
 * 会话遥测服务 —— 对应原 Harness 的 {@code session-telemetry}。
 *
 * <p>捕获会话事件用于报告：按会话聚合事件记录，支持脱敏（移除敏感字段）
 * 后交接给报告后端（如 OpenTelemetry、日志聚合）。
 *
 * <p>设计模式：观察者（事件捕获）+ 模板方法（插件基类）。
 */
public final class SessionTelemetryService
        extends AbstractCapabilityPlugin<SessionTelemetryBackend>
        implements SessionTelemetryBackend {

    private final ConcurrentMap<SessionId, List<SessionEvent>> captured = new ConcurrentHashMap<>();

    /** 脱敏字段名集合（不记录这些工具的参数）。 */
    private final java.util.Set<String> redactTools;

    public SessionTelemetryService() {
        this(java.util.Set.of());
    }

    public SessionTelemetryService(java.util.Set<String> redactTools) {
        this.redactTools = redactTools;
    }

    @Override
    protected Class<SessionTelemetryBackend> serviceType() {
        return SessionTelemetryBackend.class;
    }

    @Override
    public void capture(SessionEvent event) {
        SessionEvent redacted = redact(event);
        captured.computeIfAbsent(event.sessionId(), k -> new ArrayList<>())
                .add(redacted);
    }

    @Override
    public List<SessionEvent> records(SessionId sessionId) {
        return List.copyOf(captured.getOrDefault(sessionId, List.of()));
    }

    @Override
    public void flush(SessionId sessionId) {
        // 交接给外部报告后端（OTel 等）；此处清空缓冲
        captured.remove(sessionId);
    }

    /** 脱敏：对敏感工具的参数字段替换为 [REDACTED]。 */
    private SessionEvent redact(SessionEvent e) {
        if (e.type() == SessionEvent.Type.TOOL_CALL
                && e.payload().toolName() != null
                && redactTools.contains(e.payload().toolName())) {
            return new SessionEvent(e.seq(), e.sessionId(), e.type(),
                    SessionEvent.Payload.toolCall(e.payload().toolName(),
                            e.payload().toolCallId(),
                            java.util.Map.of("[redacted]", true)),
                    e.createdAt(), e.lineage());
        }
        return e;
    }
}
