package com.deepseek.dsh.context.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * 时间上下文插件 —— 对应原 Harness 的 {@code dsh-time-context}。
 *
 * <p>在系统提示组装时注入当前时间 + 会话已用时。
 * 渲染格式对齐 TS：{@code Time sampled while preparing turn N, step S: ...}
 *
 * <p>设计模式：观察者（监听系统提示注入事件）。
 */
public final class TimeContextPlugin implements Plugin, Service {

    private static final Logger log = LoggerFactory.getLogger(TimeContextPlugin.class);

    private final Instant sessionStart = Instant.now();
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public Disposable apply(Context ctx) {
        ctx.events().on(SystemPromptInjectEvent.class, (event, next) -> {
            String timeText = renderTime();
            event.appendSection("time", timeText);
            return next.invoke(event);
        });
        return () -> {};
    }

    private String renderTime() {
        Instant now = Instant.now();
        long elapsedSec = now.getEpochSecond() - sessionStart.getEpochSecond();
        String formatted = FORMATTER.format(ZonedDateTime.from(now.atZone(ZoneId.systemDefault())));
        String elapsed = formatDuration(elapsedSec);
        return "当前时间: " + formatted + " (" + ZoneId.systemDefault() + ")\n"
                + "会话已用时: " + elapsed;
    }

    /** 格式化秒数为紧凑时长（如 1d 2h 3m 4s）。 */
    private static String formatDuration(long seconds) {
        seconds = Math.max(0, seconds);
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        parts.add(seconds + "s");
        return String.join(" ", parts);
    }
}
