package com.deepseek.dsh.core.exception;

/**
 * LLM 调用异常 —— 模型请求失败时抛出。
 *
 * <p>携带模型名与 HTTP 状态码（若适用）。
 * 5xx/超时可恢复；4xx/鉴权失败不可恢复。
 */
public class LlmException extends DshException {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    public LlmException(String model, int httpStatus, String message, Throwable cause) {
        super("llm.chat", model, message, cause, isRecoverableStatus(httpStatus));
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** HTTP 状态码是否可重试：5xx 与超时可重试，4xx 不可恢复。 */
    private static boolean isRecoverableStatus(int status) {
        return status >= 500 || status == 0; // 0 表示超时/网络错误
    }
}
