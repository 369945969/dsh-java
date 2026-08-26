package com.deepseek.dsh.capability.terminal;

import java.util.Optional;

/**
 * 持久终端能力缝 —— 对应原 Harness 的 {@code ctx.terminal}。
 *
 * <p>与一次性 shell（{@code dsh-capability-shell}）不同：持久终端会话跨多次工具调用
 * 保持进程状态（环境变量、当前目录、后台进程），适合交互式调试与长任务。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code BashTerminalProvider}（bash 进程持久会话）。</li>
 *   <li><b>消费者</b>：{@code terminal} 工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface TerminalCapability {

    /** 创建一个新的持久终端会话，返回会话 ID。 */
    String createSession(String cwd);

    /** 向指定会话写入输入并等待输出（带超时）。 */
    TerminalOutput send(String sessionId, String input, int timeoutSeconds);

    /** 读取指定会话的待输出（非阻塞）。 */
    TerminalOutput read(String sessionId);

    /** 销毁指定会话。 */
    void destroy(String sessionId);

    /** 列出全部活跃会话 ID。 */
    java.util.List<String> listSessions();

    /** 会话输出。 */
    record TerminalOutput(String text, boolean isEof) {
        public static TerminalOutput of(String text) {
            return new TerminalOutput(text, false);
        }

        public static TerminalOutput eof() {
            return new TerminalOutput("", true);
        }
    }
}
