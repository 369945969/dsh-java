package com.deepseek.dsh.web.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * 会话事件录制器 —— 抽取「追加会话日志 + 磁盘持久化」的公共逻辑，
 * 供 {@link AgentStreamController} 与 {@link AgentController} 等非 apiproxy 对话端点复用。
 *
 * <p>这两个端点原先只流式/同步返回回复，从不把对话写进 SessionLog，
 * 导致刷新后历史（{@code GET /api/agent/sessions/{id}/messages}，从日志投影）丢失。
 * 此组件让每条对话端点都以与 apiproxy {@code session/event} 相同的形态落日志并持久化。
 *
 * <p>设计模式：模板方法（事件录制）+ 依赖注入。
 */
@Component
public class SessionEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(SessionEventRecorder.class);

    /**
     * 把一条会话事件追加到 SessionLog 并持久化（静默容错，不影响对话主流程）。
     *
     * @param ctx      当前上下文
     * @param sessionId 会话 id
     * @param type      事件类型（如 {@code user/message}、{@code assistant/message}）
     * @param data      事件负载
     */
    public void record(Context ctx, String sessionId, String type, Map<String, Object> data) {
        try {
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog slog = sessions.getOrCreate(SessionId.of(sessionId));
            String surfaceOp = isSurfaceMessageEvent(type) ? "append" : null;
            SessionEvent appended = slog.append(type, data, surfaceOp);
            sessions.persist(appended);
        } catch (Exception e) {
            log.debug("SessionEventRecorder record ({}): {}", type, e.toString());
        }
    }

    /** surface 消息事件须带 surfaceOp:'append'，否则前端 isAppendSurfaceEvent 判否、消息节点不匹配 → 不渲染。 */
    private static boolean isSurfaceMessageEvent(String type) {
        return "user/message".equals(type) || "assistant/message".equals(type) || "tool/result".equals(type);
    }
}
