package com.deepseek.dsh.context.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;

/**
 * 时间上下文插件 —— 对应原 Harness 的 {@code time-context}。
 *
 * <p>在每个 step 向系统提示注入当前时间与会话已用时长，
 * 让模型感知时间流逝。
 *
 * <p>设计模式：观察者（监听 step 事件注入上下文）。
 */
public final class TimeContextPlugin implements Plugin, Service {

    private final Instant sessionStart = Instant.now();

    @Override
    public Disposable apply(Context ctx) {
        ctx.events().on(com.deepseek.dsh.context.instructions.AgentInstructionsPlugin.SystemPromptInjectEvent.class,
                (event, next) -> {
                    String now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .format(Instant.now().atZone(ZoneId.systemDefault()));
                    long elapsed = (Instant.now().getEpochSecond() - sessionStart.getEpochSecond());
                    event.appendSection("time", "当前时间: " + now
                            + "\n会话已用时: " + elapsed + " 秒");
                    return next.invoke(event);
                });
        return () -> {};
    }
}
