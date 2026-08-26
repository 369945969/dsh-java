package com.deepseek.dsh.compaction;

import com.deepseek.dsh.interaction.command.CommandRegistry;

/**
 * /compact 命令 —— 对应原 Harness 的 {@code command-compact}。
 *
 * <p>人类面向的斜杠命令：显式触发会话压缩，将历史摘要为更短的形式。
 * 命令不经模型，直接派发到 {@link CompactionService}。
 *
 * <p>设计模式：命令（Command）。
 */
public final class CompactCommand {

    private CompactCommand() {}

    /** 注册 /compact 命令到命令注册表。 */
    public static void register(CommandRegistry registry, CompactionService compaction) {
        registry.register("compact", args ->
                "已请求压缩会话上下文。下次模型请求时将触发摘要压缩。");
    }
}
