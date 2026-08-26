package com.deepseek.dsh.sdk.protocol;

/**
 * JSON-RPC 错误响应异常 —— 对应对端返回的 {@code error} 帧。
 *
 * @param code    线协议错误码（-32601 方法未找到 / -32603 内部错误 / -32700 解析错误等）
 * @param message 错误消息
 */
public class JsonRpcException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
