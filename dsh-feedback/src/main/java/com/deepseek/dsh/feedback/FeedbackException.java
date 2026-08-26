package com.deepseek.dsh.feedback;

/**
 * 反馈业务失败 —— 携带稳定的机器可读错误码，对应原 Harness 的
 * {@code MessageFeedbackFailure} 联合。
 *
 * <p>稳定码：
 * <ul>
 *   <li>{@code session_not_found} —— 请求的会话不存在。</li>
 *   <li>{@code target_not_found} —— 目标消息不是一条已定稿的助手消息。</li>
 *   <li>{@code version_conflict} —— 物质变更未匹配到当前项的版本。</li>
 *   <li>{@code note_blank} —— 提供的备注不含任何非空白字符。</li>
 *   <li>{@code note_too_large} —— 提供的备注超过配置的 UTF-8 字节上限。</li>
 *   <li>{@code service_disposing} —— 服务正在释放，拒绝新变更。</li>
 * </ul>
 *
 * <p>设计模式：值对象（错误码封闭集）+ 异常（控制流）。
 */
public class FeedbackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        SESSION_NOT_FOUND("session_not_found"),
        TARGET_NOT_FOUND("target_not_found"),
        VERSION_CONFLICT("version_conflict"),
        NOTE_BLANK("note_blank"),
        NOTE_TOO_LARGE("note_too_large"),
        SERVICE_DISPOSING("service_disposing");

        private final String wire;

        Code(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final Code code;
    private final MessageFeedbackItem conflictingCurrent;

    public FeedbackException(Code code, String message) {
        this(code, message, null);
    }

    public FeedbackException(Code code, String message, MessageFeedbackItem conflictingCurrent) {
        super(message);
        this.code = code;
        this.conflictingCurrent = conflictingCurrent;
    }

    public Code code() {
        return code;
    }

    /** 仅 {@code version_conflict} 时为权威当前项，否则为 {@code null}。 */
    public MessageFeedbackItem conflictingCurrent() {
        return conflictingCurrent;
    }
}
