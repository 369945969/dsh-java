package com.deepseek.dsh.session.telemetry;

import java.util.List;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.session.log.SessionEvent;

/**
 * 会话遥测后端缝 —— 对应原 Harness 的 {@code SessionTelemetryBackend}。
 *
 * <p>提供者可对接 OpenTelemetry、日志聚合等。
 */
public interface SessionTelemetryBackend extends Service {

    /** 捕获一条会话事件。 */
    void capture(SessionEvent event);

    /** 获取某会话的全部捕获记录。 */
    List<SessionEvent> records(SessionId sessionId);

    /** 刷新（交接给外部后端并清空缓冲）。 */
    void flush(SessionId sessionId);
}
