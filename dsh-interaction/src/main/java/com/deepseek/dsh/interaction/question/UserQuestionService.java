package com.deepseek.dsh.interaction.question;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;

/**
 * 用户提问服务能力缝 —— 对应原 Harness 的 {@code user-questions}。
 *
 * <p>让 agent 在执行中向用户提问并异步等待回答。默认实现自动选择首个选项。
 */
public interface UserQuestionService extends Service {

    /** 向用户提问，异步返回选中的标签列表。 */
    CompletableFuture<List<String>> ask(UserQuestion question);

    /** 默认自动实现（headless）。 */
    final class AutoUserQuestionService implements UserQuestionService, Plugin {
        @Override
        public CompletableFuture<List<String>> ask(UserQuestion question) {
            List<String> first = question.options().isEmpty()
                    ? List.of() : List.of(question.options().get(0).label());
            return CompletableFuture.completedFuture(first);
        }

        @Override
        public Disposable apply(Context ctx) {
            return ctx.register(UserQuestionService.class, this);
        }
    }
}
