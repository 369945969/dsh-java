package com.deepseek.dsh.interaction.command;

import java.util.Optional;

import com.deepseek.dsh.core.context.Service;

/**
 * 命令服务能力缝 —— 对应原 Harness 的 {@code ctx.commands}。
 */
public interface Commands extends Service {

    /** 按名获取命令处理者。 */
    Optional<CommandRegistry.CommandHandler> get(String name);
}
