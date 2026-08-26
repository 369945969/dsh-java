package com.deepseek.dsh.schedule;

/**
 * 调度工具错误 —— 携带稳定的机器可读错误码，对应原 Harness 的
 * {@code ScheduleToolError} 联合。
 *
 * <p>稳定码：
 * <ul>
 *   <li>{@code invalid_prompt} —— 提醒内容为空。</li>
 *   <li>{@code invalid_selector} —— 缺少、冲突或不支持的规则选择器。</li>
 *   <li>{@code invalid_rule} —— 无效的规则或参数。</li>
 *   <li>{@code invalid_time_zone} —— 无效或不支持的 IANA 时区。</li>
 *   <li>{@code not_future} —— 绝对目标不严格在未来。</li>
 *   <li>{@code time_out_of_range} —— 计算时刻无法用四位数 UTC 年份表示。</li>
 *   <li>{@code frequency_too_high} —— 固定速率规则比支持的更频繁。</li>
 *   <li>{@code corrupt_schedule_log} —— 持久调度流损坏。</li>
 *   <li>{@code persistence_uncertain} —— 必要的持久检查点未完成。</li>
 *   <li>{@code internal_error} —— 不安全暴露内部异常的稳定兜底。</li>
 * </ul>
 */
public class ScheduleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_PROMPT("invalid_prompt"),
        INVALID_SELECTOR("invalid_selector"),
        INVALID_RULE("invalid_rule"),
        INVALID_TIME_ZONE("invalid_time_zone"),
        NOT_FUTURE("not_future"),
        TIME_OUT_OF_RANGE("time_out_of_range"),
        FREQUENCY_TOO_HIGH("frequency_too_high"),
        CORRUPT_SCHEDULE_LOG("corrupt_schedule_log"),
        PERSISTENCE_UNCERTAIN("persistence_uncertain"),
        INTERNAL_ERROR("internal_error"),
        SCHEDULE_NOT_FOUND("schedule_not_found");

        private final String wire;

        Code(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final Code code;

    public ScheduleException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** 持久日志损坏（重放/解码失败）。 */
    public static ScheduleException corruptLog(String message) {
        return new ScheduleException(Code.CORRUPT_SCHEDULE_LOG, message);
    }

    /** 输入校验失败。 */
    public static ScheduleException input(Code code, String message) {
        return new ScheduleException(code, message);
    }
}
