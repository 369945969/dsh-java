package com.deepseek.dsh.interaction.permission;

import java.util.Set;

/**
 * 权限预设 —— 对应原 Harness 的 permission presets。
 *
 * <p>定义在某作用域下，哪些工具/资源被允许、拒绝或需要审批。
 */
public record PermissionPreset(
        /** 预设名。 */
        String name,
        /** 允许的工具集（空表示全部允许）。 */
        Set<String> allow,
        /** 显式拒绝的工具集。 */
        Set<String> deny,
        /** 需要审批的工具集。 */
        Set<String> ask
) {
    /** 判定某工具的权限决策。 */
    public Decision decide(String toolName) {
        if (deny.contains(toolName)) return Decision.DENY;
        if (ask.contains(toolName)) return Decision.ASK;
        if (allow.isEmpty() || allow.contains(toolName)) return Decision.ALLOW;
        return Decision.DENY;
    }

    public enum Decision { ALLOW, ASK, DENY }
}
