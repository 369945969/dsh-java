package com.deepseek.dsh.capability.shell;

import java.util.List;

/**
 * Shell 执行结果。
 */
public record ShellResult(
        /** 标准输出。 */
        String stdout,
        /** 标准错误。 */
        String stderr,
        /** 退出码。 */
        int exitCode,
        /** 是否因超时被杀。 */
        boolean timedOut
) {
    public static ShellResult of(String stdout, String stderr, int exitCode) {
        return new ShellResult(stdout, stderr, exitCode, false);
    }

    /** 是否成功（退出码 0）。 */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** 合并 stdout + stderr 为单段文本（带标注）。 */
    public String combinedOutput() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) sb.append(stdout);
        if (stderr != null && !stderr.isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("[stderr]\n").append(stderr);
        }
        if (exitCode != 0) sb.append("\n[exit=").append(exitCode).append(']');
        return sb.toString();
    }
}
