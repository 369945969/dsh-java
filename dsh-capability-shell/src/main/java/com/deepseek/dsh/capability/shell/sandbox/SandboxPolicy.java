package com.deepseek.dsh.capability.shell.sandbox;

import java.util.regex.Pattern;

/**
 * 沙箱策略 —— 对应原 Harness 的沙箱模式配置。
 *
 * <p>定义命令权限检查规则：
 * <ul>
 *   <li>{@link #ALLOW} — 允许所有命令</li>
 *   <li>{@link #defaultPolicy()} — 默认策略（拒绝危险命令）</li>
 * </ul>
 *
 * <p>设计模式：策略（可替换的权限规则）。
 */
public final class SandboxPolicy {

    public enum Decision { ALLOW, DENY }

    /** 允许所有命令。 */
    public static final SandboxPolicy ALLOW = new SandboxPolicy(null);

    private final Pattern denyPattern;

    private SandboxPolicy(Pattern denyPattern) {
        this.denyPattern = denyPattern;
    }

    /** 默认策略：拒绝 rm -rf /、shutdown、reboot 等危险命令。 */
    public static SandboxPolicy defaultPolicy() {
        return new SandboxPolicy(Pattern.compile(
                "\\brm\\s+-rf\\s+/|\\bshutdown|\\breboot|\\bmkfs|\\bdd\\s+if="
        ));
    }

    /** 自定义拒绝模式。 */
    public static SandboxPolicy deny(Pattern pattern) {
        return new SandboxPolicy(pattern);
    }

    /** 检查命令是否允许。 */
    public Decision check(String command) {
        if (denyPattern == null) return Decision.ALLOW;
        return denyPattern.matcher(command).find() ? Decision.DENY : Decision.ALLOW;
    }
}
