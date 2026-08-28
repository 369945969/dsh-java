package com.deepseek.dsh.feedback;

import java.util.Map;

import com.deepseek.dsh.session.log.SessionEvent;

/**
 * 反馈记录事件 —— 对应原 Harness 的 {@code feedback/record} 会话事件。
 *
 * <p>一条关于本会话的人类备注。仅日志、独立于触发器；它从不进入模型上下文
 * 或派生历史。本类封装 {@code feedback/record} 事件负载的编码与解码，
 * 以 {@link SessionEvent.Type#COMMAND} 携带结构化负载。
 *
 * <p>设计模式：值对象（事件负载编解码）。
 */
public final class FeedbackRecord {

    /** 事件负载中的反馈动作标记。 */
    public static final String ACTION = "feedback/record";

    private FeedbackRecord() {
    }

    /** 把一条已规整化的反馈文本编码为 COMMAND 事件的结构化负载。 */
    public static SessionEvent.Payload encode(String text) {
        return new SessionEvent.Payload(null, Map.of("feedback", "record", "text", text), null, null, null);
    }

    /** 从 COMMAND 事件的结构化负载中提取反馈文本（若为 feedback/record 动作）。 */
    public static String decode(SessionEvent.Payload payload) {
        if (payload == null || payload.structured() == null) {
            return null;
        }
        Object action = payload.structured().get("feedback");
        if (!"record".equals(action)) {
            return null;
        }
        Object text = payload.structured().get("text");
        return text == null ? null : text.toString();
    }
}
