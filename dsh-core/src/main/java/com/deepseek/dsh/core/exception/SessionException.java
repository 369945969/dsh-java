package com.deepseek.dsh.core.exception;

/**
 * 会话/持久化异常 —— 事件日志追加、重放、查询失败时抛出。
 */
public class SessionException extends DshException {

    private static final long serialVersionUID = 1L;

    public SessionException(String operation, String message) {
        super(operation, message);
    }

    public SessionException(String operation, String message, Throwable cause) {
        super(operation, null, message, cause, false);
    }

    public SessionException(String operation, String sessionId, String message, Throwable cause) {
        super(operation, sessionId, message, cause, false);
    }
}
