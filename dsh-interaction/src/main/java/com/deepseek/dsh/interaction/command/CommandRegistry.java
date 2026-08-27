package com.deepseek.dsh.interaction.command;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;

/**
 * 命令服务能力缝 —— 对应原 Harness 的 {@code ctx.commands}。
 *
 * <p>斜杠前缀的人类命令（如 {@code /goal}、{@code /compact}），不经模型直接派发。
 * 命令注册即一个可逆副作用。
 *
 * <p>设计模式：命令（Command）—— 每条命令是一个可派发的处理者。
 */
public final class CommandRegistry implements Plugin, Commands, Service {

    private final ConcurrentMap<String, CommandHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public Disposable apply(Context ctx) {
        return ctx.register(Commands.class, this);
    }

    /** 注册一条命令（可逆）。 */
    public Disposable register(String name, CommandHandler handler) {
        CommandHandler prev = handlers.putIfAbsent(name, handler);
        if (prev != null) throw new IllegalStateException("Command already registered: " + name);
        return () -> handlers.remove(name);
    }

    @Override
    public Optional<CommandHandler> get(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    /** 命令处理者。 */
    @FunctionalInterface
    public interface CommandHandler {
        /**
         * 处理一条命令。
         * @param args 命令参数（斜杠之后按空格分割）
         * @return 回显给用户的文本
         */
        String handle(String[] args);
    }
}
