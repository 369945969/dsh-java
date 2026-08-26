package com.deepseek.dsh.core.exception;

/**
 * 能力缝异常 —— 能力提供者初始化或执行失败时抛出。
 *
 * <p>携带能力名（如 "shell"、"fs"、"lsp"），便于定位是哪个能力缝出问题。
 */
public class CapabilityException extends DshException {

    private static final long serialVersionUID = 1L;

    public CapabilityException(String capability, String message, Throwable cause) {
        super("capability." + capability, message, cause);
    }

    public CapabilityException(String capability, String target, String message, Throwable cause) {
        super("capability." + capability, target, message, cause, false);
    }
}
