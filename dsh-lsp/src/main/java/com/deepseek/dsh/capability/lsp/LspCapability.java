package com.deepseek.dsh.capability.lsp;

import java.util.List;
import java.util.Map;

/**
 * LSP 能力缝 —— 对应原 Harness 的 {@code ctx.lsp}。
 *
 * <p>桥接语言服务器协议（LSP），提供符号查询、定义跳转、诊断等能力。
 * 实现通过 stdio 与外部语言服务器进程通信。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code LspStdioProvider}。</li>
 *   <li><b>消费者</b>：{@code lsp} 工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + 适配器（LSP JSON-RPC ↔ 内部能力）。
 */
public interface LspCapability {

    /** 启动一个语言服务器（如 pyright、clangd）。 */
    String startServer(String command, String workspaceRoot);

    /** 查询某符号的定义位置。 */
    List<Location> findDefinitions(String serverId, String filePath, int line, int character);

    /** 查询某文件中的诊断（错误/警告）。 */
    List<Diagnostic> diagnostics(String serverId, String filePath);

    /** 关闭语言服务器。 */
    void stopServer(String serverId);

    /** 位置信息。 */
    record Location(String filePath, int line, int character) {}

    /** 诊断信息。 */
    record Diagnostic(int line, String severity, String message) {}

    /** LSP 请求参数（简化）。 */
    record LspRequest(String method, Map<String, Object> params) {}
}
