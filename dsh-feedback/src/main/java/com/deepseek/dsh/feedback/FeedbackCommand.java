package com.deepseek.dsh.feedback;

import java.util.function.Supplier;

import com.deepseek.dsh.interaction.command.CommandRegistry.CommandHandler;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * /feedback 命令 —— 对应原 Harness 的 {@code command-feedback}。
 *
 * <p>记录一条会话反馈事件：验证非空、去空白后向会话日志追加一条
 * {@code feedback/record}（仅日志）事件，并回执包含会话 ID 的确认。
 * 追加是急切但未刷新的，因此回执报告该条目已记录而非已落盘。
 *
 * <p>原 TS 版在回执中附带匿名用户 ID 与会话共享披露（来自遥测后端）。
 * 本 Java 移植省略遥测披露（遥测为可选能力），回执仅含会话 ID。
 *
 * <p>设计模式：命令（Command）—— 验证、记录、回执一条反馈条目。
 */
public final class FeedbackCommand {

    private static final String USAGE = "Usage: /feedback <text>";

    private FeedbackCommand() {
    }

    /**
     * 独立于任何 UI 触发器记录反馈。
     *
     * @param session 反馈所描述的会话
     * @param text    人类编写的反馈；首尾空白被丢弃
     */
    public static void recordFeedback(SessionLog session, String text) {
        if (text == null) {
            throw new IllegalArgumentException("feedback text must not be null");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("feedback text must not be empty");
        }
        session.append(FeedbackRecord.EVENT_TYPE, FeedbackRecord.encode(normalized));
    }

    /**
     * 验证、记录并回执一条反馈条目。返回错误时不留下 feedback/record 事件。
     *
     * @param session 反馈所描述的会话
     * @param rawInput 原始命令输入（/feedback 之后的文本）
     * @return 确认文本或用法错误文本
     */
    public static String execute(SessionLog session, String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return "Feedback text is required. " + USAGE;
        }
        recordFeedback(session, rawInput);
        return "Feedback recorded for session " + session.sessionId().value();
    }

    /**
     * 创建一条绑定到指定会话供应者的 /feedback 命令处理者。
     * 适合在命令注册表中按 agent 作用域注册。
     *
     * @param sessionSupplier 返回当前命令所属会话的供应者
     * @return 命令处理者（接收 /feedback 之后的参数数组）
     */
    public static CommandHandler handler(Supplier<SessionLog> sessionSupplier) {
        return args -> {
            SessionLog session = sessionSupplier.get();
            if (session == null) {
                return "No active session to record feedback for.";
            }
            String rawInput = args == null || args.length == 0 ? "" : String.join(" ", args);
            return execute(session, rawInput);
        };
    }
}
