package com.deepseek.dsh.interaction.permission;

import java.util.Set;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;

/**
 * 权限服务能力缝 —— 对应原 Harness 的 {@code ctx.permission}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@link DefaultPermissionService}（预设驱动）。</li>
 *   <li><b>消费者</b>：工具执行管线的权限中间件。</li>
 * </ul>
 *
 * <p>设计模式：责任链（按预设链逐级判定）+ 代理。
 */
public interface PermissionService extends Service {

    /** 判定某工具在某作用域下的权限决策。 */
    PermissionPreset.Decision check(String toolName, String scopeKeyId);

    /** 注册一个预设到链中。 */
    Disposable registerPreset(PermissionPreset preset);

    /** 默认实现：单一预设 + 默认放行。 */
    final class DefaultPermissionService implements PermissionService, Plugin {

        private final java.util.List<PermissionPreset> presets =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final PermissionPreset defaultPreset = new PermissionPreset(
                "default", Set.of(), Set.of(), Set.of());

        @Override
        public PermissionPreset.Decision check(String toolName, String scopeKeyId) {
            for (PermissionPreset p : presets) {
                PermissionPreset.Decision d = p.decide(toolName);
                if (d != PermissionPreset.Decision.ALLOW) return d;
            }
            return defaultPreset.decide(toolName);
        }

        @Override
        public Disposable registerPreset(PermissionPreset preset) {
            presets.add(preset);
            return () -> presets.remove(preset);
        }

        @Override
        public Disposable apply(Context ctx) {
            return ctx.register(PermissionService.class, this);
        }
    }
}
