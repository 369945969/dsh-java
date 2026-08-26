package com.deepseek.dsh.capability.sandbox;

import java.util.Map;

/**
 * 进程沙箱能力缝 —— 对应原 Harness 的 {@code ctx.sandbox}。
 *
 * <p>提供进程级隔离：限制子进程的文件系统访问、网络、能力等。
 * 不同后端实现不同 OS 的隔离机制：
 * <ul>
 *   <li>Linux: Landlock / Bubblewrap(bwrap)</li>
 *   <li>macOS: Seatbelt(sandbox-exec)</li>
 *   <li>Windows: ACL restricted-token</li>
 * </ul>
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code LocalSandboxProvider}（按 OS 自动选择）。</li>
 *   <li><b>消费者</b>：{@code bash-sandbox} / {@code fs-sandbox} 等沙箱消费能力。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface SandboxCapability {

    /**
     * 在沙箱约束下执行一个命令。
     *
     * @param command       命令数组
     * @param environment   环境变量
     * @param workingDir    工作目录
     * @param timeoutSeconds 超时秒数
     * @param policy        沙箱策略（允许/拒绝的路径等）
     */
    com.deepseek.dsh.core.process.ExecutionResult execute(
            String[] command, Map<String, String> environment,
            String workingDir, int timeoutSeconds, SandboxPolicy policy);

    /** 沙箱策略 —— 定义允许/拒绝的文件路径、网络等。 */
    record SandboxPolicy(
            /** 允许读的路径前缀（空表示全部允许）。 */
            java.util.List<String> allowReadPaths,
            /** 允许写的路径前缀。 */
            java.util.List<String> allowWritePaths,
            /** 是否允许网络访问。 */
            boolean allowNetwork
    ) {
        public static SandboxPolicy permissive() {
            return new SandboxPolicy(java.util.List.of(), java.util.List.of(), true);
        }

        public static SandboxPolicy restricted(java.util.List<String> writePaths) {
            return new SandboxPolicy(java.util.List.of(), writePaths, false);
        }
    }
}
