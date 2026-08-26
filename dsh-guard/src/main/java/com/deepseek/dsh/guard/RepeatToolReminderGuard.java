package com.deepseek.dsh.guard;

import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.pipeline.ToolMiddleware;

/**
 * 重复工具调用守卫 —— 对应原 Harness 的 {@code repeat-tool-reminder}。
 *
 * <p>当 agent 连续多次（超过阈值）调用同一工具的相同参数时，
 * 在结果前插入提醒，促使 agent 改变策略而非死循环。
 *
 * <p>设计模式：责任链中间件（装饰工具执行管线）。
 */
public final class RepeatToolReminderGuard implements ToolMiddleware {

    /** 连续相同调用的容忍次数。 */
    private final int threshold;
    /** 最近一次调用（工具名+参数哈希）。 */
    private String lastCallFingerprint;
    private int repeatCount = 0;

    public RepeatToolReminderGuard() {
        this(3);
    }

    public RepeatToolReminderGuard(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public ToolExecutionResult handle(ToolExecutionRequest request,
                                      Next<ToolExecutionRequest, ToolExecutionResult> next) {
        String fingerprint = request.toolName() + "|" + request.arguments().hashCode();
        if (fingerprint.equals(lastCallFingerprint)) {
            repeatCount++;
        } else {
            repeatCount = 0;
            lastCallFingerprint = fingerprint;
        }

        ToolExecutionResult result = next.proceed(request);

        if (repeatCount >= threshold) {
            String reminder = "\n⚠️ 提示：已连续 " + (repeatCount + 1)
                    + " 次以相同参数调用 " + request.toolName()
                    + "，请考虑改变策略或检查结果。";
            return new ToolExecutionResult(
                    result.toolCallId(), result.text() + reminder, result.isError());
        }
        return result;
    }
}
