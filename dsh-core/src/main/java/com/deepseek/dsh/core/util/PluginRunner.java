package com.deepseek.dsh.core.util;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;

/**
 * 插件启动器 —— 按序挂载一组 {@link Plugin} 到 {@link Context}，
 * 并记录每个插件返回的 {@link Disposable}，便于统一卸载。
 *
 * <p>设计模式：构建器（组合一组插件为一个可启动单元）。
 */
public final class PluginRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginRunner.class);

    private final List<Plugin> plugins = new ArrayList<>();
    private final List<Disposable> disposables = new ArrayList<>();

    /** 添加一个待挂载的插件（挂载发生在 {@link #start} 时）。 */
    public PluginRunner add(Plugin plugin) {
        plugins.add(plugin);
        return this;
    }

    /** 将所有插件挂载到给定上下文。 */
    public void start(Context ctx) {
        for (Plugin p : plugins) {
            log.debug("挂载插件: {}", p.getClass().getSimpleName());
            Disposable d = p.apply(ctx);
            disposables.add(d);
        }
    }

    /** 按逆序卸载所有插件。 */
    public void stop() {
        for (int i = disposables.size() - 1; i >= 0; i--) {
            try {
                disposables.get(i).dispose();
            } catch (RuntimeException e) {
                log.warn("卸载插件时出错", e);
            }
        }
        disposables.clear();
    }
}
