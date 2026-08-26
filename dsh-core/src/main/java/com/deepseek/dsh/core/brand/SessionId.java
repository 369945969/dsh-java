package com.deepseek.dsh.core.brand;

/**
 * 会话 ID 的品牌化类型。
 */
public final class SessionId extends Branded<String, SessionId.Tag> {
    private SessionId(String value) {
        super(value);
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static SessionId of(String raw) {
        return new SessionId(raw);
    }

    /** 幻影标签标记 —— 仅供类型系统使用。 */
    public static final class Tag {}
}
