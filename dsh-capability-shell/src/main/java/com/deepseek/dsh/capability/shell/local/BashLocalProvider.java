package com.deepseek.dsh.capability.shell.local;

import java.util.Map;

import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.capability.shell.ShellResult;
import com.deepseek.dsh.core.process.ExecutionResult;
import com.deepseek.dsh.core.process.ProcessRunner;

/**
 * 本地 bash 提供者 —— 对应原 Harness 的 {@code dsh-bash-local}。
 *
 * <p><b>重构后</b>：进程启动/输出排空/超时逻辑全部委托给共用的 {@link ProcessRunner}，
 * 本类仅负责命令组装与结果映射，消除此前逐字节重复的 drain 样板。
 *
 * <p>设计模式：策略的具体实现（委托给 ProcessRunner 工具）。
 */
public final class BashLocalProvider implements ShellCapability {

    @Override
    public ShellResult execute(String command, Map<String, String> env, String cwd, int timeoutSeconds) {
        ExecutionResult result = ProcessRunner.run(
                new String[]{"bash", "-c", command}, env, cwd, timeoutSeconds, "shell");
        return toShellResult(result);
    }

    /** {@link ExecutionResult} → {@link ShellResult}（保持外部 API 不变）。 */
    private static ShellResult toShellResult(ExecutionResult r) {
        return new ShellResult(r.stdout(), r.stderr(), r.exitCode(), r.timedOut());
    }
}
