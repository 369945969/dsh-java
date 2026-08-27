package com.deepseek.dsh.tools.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 工具注册表 —— 对应原 Harness 的 {@code ctx.tools}。
 *
 * <p>注册的工具 schema 在装配模型提示时自动加入；工具执行经过可拦截的管线。
 * 注册即一个可逆副作用：返回的 {@link Disposable} 可注销该工具。
 *
 * <p>设计模式：注册表（Registry）—— 全局与作用域两级，作用域注册遮蔽全局。
 */
public final class ToolRegistry implements Plugin, Tools, Service {

    private final Map<String, Tool> global = new ConcurrentHashMap<>();
    private final Map<String, Disposable> globalDisposables = new ConcurrentHashMap<>();

    @Override
    public Disposable apply(Context ctx) {
        return ctx.register(Tools.class, this);
    }

    /** 全局注册一个工具。返回可注销句柄。 */
    public Disposable register(Tool tool) {
        String name = tool.schema().name();
        Tool prev = global.putIfAbsent(name, tool);
        if (prev != null) {
            throw new IllegalStateException("Tool already registered: " + name);
        }
        Disposable d = () -> {
            if (global.remove(name) != null) {
                globalDisposables.remove(name);
            }
        };
        globalDisposables.put(name, d);
        return d;
    }

    @Override
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(global.get(name));
    }

    @Override
    public List<ToolSchema> schemas() {
        return global.values().stream()
                .map(Tool::schema)
                .toList();
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(global.keySet());
    }

    /** 列出全部已注册工具（按注册顺序）。 */
    public List<Tool> all() {
        return List.copyOf(global.values());
    }
}
