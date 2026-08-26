package com.deepseek.dsh.feedback;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消息反馈评级 —— 对应原 Harness 的 {@code MessageFeedbackRating}。
 *
 * <p>封闭的二元判定：正面或负面。
 *
 * <p>设计模式：值对象（枚举封闭集）。
 */
public enum FeedbackRating {

    POSITIVE("positive"),
    NEGATIVE("negative");

    private final String wire;

    FeedbackRating(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static FeedbackRating of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("feedback rating must not be null");
        }
        for (FeedbackRating r : values()) {
            if (r.wire.equals(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("feedback rating must be 'positive' or 'negative', got: " + value);
    }
}
