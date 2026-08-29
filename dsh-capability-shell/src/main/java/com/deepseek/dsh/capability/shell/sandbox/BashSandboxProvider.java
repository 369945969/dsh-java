package com.deepseek.dsh.capability.shell.sandbox;

import java.util.Map;

import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.capability.shell.ShellResult;

/**
 * 沙箱 bash 执行提供者 —— 对应原 Harness 的 {@code dsh-bash-sandbox}。
 *
 * <p>通过沙箱策略（{@link SandboxPolicy}）限制命令执行权限，
 * 拒绝危险操作（如 rm -rf /）。镜像 {@code bash-local} 的调用结构，
 * 但在执行前过 {@link SandboxPolicy#check(String)} 权限检查。
 *
 * <p>设计模式：装饰器（在 {@code bash-local} 之上加沙箱策略）。
 */
public final class BashSandboxProvider implements ShellCapability {

    private final ShellCapability delegate;
    private final SandboxPolicy policy;

    public BashSandboxProvider(ShellCapability delegate, SandboxPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    public BashSandboxProvider(ShellCapability delegate) {
        this(delegate, SandboxPolicy.defaultPolicy());
    }

    public String name() {
        return "bash-sandbox";
    }

    @Override
    public ShellResult execute(String command, Map<String, String> env, String workdir, int timeoutSeconds) throws Exception {
        SandboxPolicy.Decision decision = policy.check(command);
        if (decision == SandboxPolicy.Decision.DENY) {
            return new ShellResult("", "sandbox: command denied by policy: " + command, 126, false);
        }
        return delegate.execute(command, env, workdir, timeoutSeconds);
    }
}
