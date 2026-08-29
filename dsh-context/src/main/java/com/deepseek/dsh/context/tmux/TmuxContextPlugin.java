package com.deepseek.dsh.context.tmux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * tmux 上下文注入插件 —— 对应原 Harness 的 {@code dsh-tmux-context}。
 *
 * <p>在系统提示组装时注入当前 tmux 会话/窗格信息（会话名、窗口名、窗格 ID、窗格布局）。
 * 仅当进程运行在 tmux 内时注入；非 tmux 环境为 no-op。
 *
 * <p>设计模式：观察者（监听系统提示注入事件）+ 策略（tmux 状态采集）。
 */
public final class TmuxContextPlugin implements Plugin, Service {

    private static final Logger log = LoggerFactory.getLogger(TmuxContextPlugin.class);

    @Override
    public Disposable apply(Context ctx) {
        ctx.events().on(SystemPromptInjectEvent.class, (event, next) -> {
            String tmuxInfo = collectTmuxInfo();
            if (tmuxInfo != null && !tmuxInfo.isBlank()) {
                event.appendSection("tmux-context", tmuxInfo);
            }
            return next.invoke(event);
        });
        return () -> {};
    }

    /** 采集 tmux 会话/窗格信息（不在 tmux 中返回 null）。 */
    private String collectTmuxInfo() {
        String tmuxPane = System.getenv("TMUX_PANE");
        if (tmuxPane == null || tmuxPane.isBlank()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append("Running inside a tmux session.\n");
        sb.append("- TMUX_PANE: ").append(tmuxPane).append("\n");

        // 采集 tmux 窗格信息（单条 tmux 命令）
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tmux", "display-message", "-p",
                    "-t", tmuxPane,
                    "#{session_name}:#{window_index}.#{pane_index} (#{window_name}) layout: #{window_layout}");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit == 0 && !output.isEmpty()) {
                sb.append("- Session/Window/Pane: ").append(output).append("\n");
            }
        } catch (Exception e) {
            log.debug("tmux display-message failed: {}", e.toString());
        }

        sb.append("</system-reminder>");
        return sb.toString();
    }
}
