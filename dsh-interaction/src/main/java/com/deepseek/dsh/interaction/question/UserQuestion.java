package com.deepseek.dsh.interaction.question;

import java.util.List;

/**
 * 向用户提问的请求 —— 对应原 Harness 的 {@code tool-ask-user} / {@code user-questions}。
 */
public record UserQuestion(
        /** 问题标题。 */
        String header,
        /** 问题正文。 */
        String question,
        /** 可选项。 */
        List<Option> options,
        /** 是否允许多选。 */
        boolean multiple
) {
    public record Option(String label, String description) {}

    public static UserQuestion single(String header, String question, List<Option> options) {
        return new UserQuestion(header, question, options, false);
    }
}
