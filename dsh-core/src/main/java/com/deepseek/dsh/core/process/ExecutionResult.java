package com.deepseek.dsh.core.process;

/**
 * 子进程执行结果 —— Shell 与代码运行的共用结果类型。
 *
 * <p>替代此前分散重复的 {@code ShellResult} 与 {@code CodeResult}，
 * 二者结构完全相同，统一为一个类型。
 */
public record ExecutionResult(
        /** 标准输出。 */
        String stdout,
        /** 标准错误。 */
        String stderr,
        /** 退出码（-1 表示超时被杀）。 */
        int exitCode,
        /** 是否因超时被杀。 */
        boolean timedOut
) {
    /** 正常完成。 */
    public static ExecutionResult of(String stdout, String stderr, int exitCode) {
        return new ExecutionResult(stdout, stderr, exitCode, false);
    }

    /** 超时。 */
    public static ExecutionResult timedOut(String partialStdout, String partialStderr) {
        return new ExecutionResult(partialStdout, partialStderr, -1, true);
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
            if (sb.length() > 0) sb.append('\n');
            sb.append("[stderr]\n").append(stderr);
        }
        if (exitCode != 0) {
            sb.append("\n[exit=").append(exitCode).append(']');
        }
        return sb.toString();
    }
}
