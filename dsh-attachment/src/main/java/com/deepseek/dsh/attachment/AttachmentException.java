package com.deepseek.dsh.attachment;

/**
 * 附件存储异常。
 */
public class AttachmentException extends RuntimeException {
    public AttachmentException(String message) { super(message); }
    public AttachmentException(String message, Throwable cause) { super(message, cause); }
}
