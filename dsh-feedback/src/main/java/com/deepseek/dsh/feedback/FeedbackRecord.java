package com.deepseek.dsh.feedback;

import java.util.Map;

/**
 * 反馈记录事件 —— 对应原 Harness 的 {@code feedback/record} 会话事件。
 *
 * <p>一条关于本会话的人类备注。以 {@code "feedback/record"} 类型的 wire 事件携带。
 */
public final class FeedbackRecord {

    public static final String ACTION = "feedback/record";
    public static final String EVENT_TYPE = "feedback/record";

    private FeedbackRecord() {}

    public static Map<String, Object> encode(String text) {
        return Map.of("feedback", "record", "text", text);
    }

    public static String decode(Map<String, Object> data) {
        if (data == null) return null;
        Object action = data.get("feedback");
        if (!"record".equals(action)) return null;
        Object text = data.get("text");
        return text == null ? null : text.toString();
    }
}
