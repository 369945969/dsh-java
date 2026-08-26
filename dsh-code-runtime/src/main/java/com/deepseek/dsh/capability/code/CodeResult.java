package com.deepseek.dsh.capability.code;

/**
 * 代码运行结果。
 */
public record CodeResult(
        /** 标准输出。 */
        String stdout,
        /** 标准错误。 */
        String stderr,
        /** 退出码。 */
        int exitCode,
        /** 是否因超时被杀。 */
        boolean timedOut
) {
    public static CodeResult of(String stdout, String stderr, int exitCode) {
        return new CodeResult(stdout, stderr, exitCode, false);
    }
}
