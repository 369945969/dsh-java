package com.deepseek.dsh.capability.shell;

import java.util.Map;

/**
 * Shell 能力缝 —— 对应原 Harness 的 {@code ctx.shell}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code BashLocalProvider}（本地）/ {@code BashSandboxProvider}（沙箱）。</li>
 *   <li><b>消费者</b>：{@code bash} 工具。</li>
 * </ul>
 *
 * <p>切换提供者即可整体迁移执行世界（本地 ↔ 沙箱），这正是能力缝的价值。
 *
 * <p>设计模式：策略 + SPI。
 */
public interface ShellCapability {

    /**
     * 执行一条 shell 命令。
     *
     * @param command 要执行的命令字符串
     * @param env     环境变量
     * @param cwd     工作目录
     * @param timeoutSeconds 超时秒数（0 表示不超时）
     */
    ShellResult execute(String command, Map<String, String> env, String cwd, int timeoutSeconds) throws Exception;
}
