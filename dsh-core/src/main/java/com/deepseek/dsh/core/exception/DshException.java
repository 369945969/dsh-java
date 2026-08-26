package com.deepseek.dsh.core.exception;

/**
 * DeepSeek Harness 异常层次根 —— 所有领域异常的基类。
 *
 * <p>设计哲学（大师级异常处理）：
 * <ul>
 *   <li><b>非受检（unchecked）</b> —— 领域异常继承 {@link RuntimeException}，
 *       避免在每一层方法签名上声明 {@code throws}，因为 agent loop 是顶层边界，
 *       中间层无法有意义地"恢复"异常，只能传播到边界并转化为工具错误/会话事件。</li>
 *   <li><b>携带上下文</b> —— 每个异常附带操作（operation）、目标（target）等诊断字段，
 *       不依赖消息字符串拼接，便于日志聚合与错误追踪。</li>
 *   <li><b>不吞异常</b> —— 禁止 {@code catch(Exception e) { } } 或 {@code sb.append(e)}，
 *       所有 catch 必须记录或重新抛出。</li>
 *   <li><b>区分可恢复与不可恢复</b> —— {@link #isRecoverable()} 指示调用方是否可重试。</li>
 * </ul>
 *
 * <p>设计模式：领域异常（Domain Exception）+ 上下文对象（Context Object）。
 */
public class DshException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 触发异常的操作名（如 "bash.execute"、"llm.chat"）。 */
    private final String operation;
    /** 操作目标（如文件路径、命令文本、模型名），可为 null。 */
    private final String target;
    /** 调用方是否可安全重试该操作。 */
    private final boolean recoverable;

    public DshException(String operation, String message) {
        this(operation, null, message, null, false);
    }

    public DshException(String operation, String message, Throwable cause) {
        this(operation, null, message, cause, false);
    }

    public DshException(String operation, String target, String message, Throwable cause, boolean recoverable) {
        super(message, cause);
        this.operation = operation;
        this.target = target;
        this.recoverable = recoverable;
    }

    /** 触发异常的操作名。 */
    public String operation() {
        return operation;
    }

    /** 操作目标（文件路径/命令/模型名等），可能为 null。 */
    public String target() {
        return target;
    }

    /** 调用方是否可安全重试。 */
    public boolean isRecoverable() {
        return recoverable;
    }

    @Override
    public String toString() {
        String base = getClass().getSimpleName() + "[" + operation
                + (target != null ? ":" + target : "") + "] " + getMessage();
        return getCause() != null ? base + " (cause: " + getCause() + ")" : base;
    }
}
