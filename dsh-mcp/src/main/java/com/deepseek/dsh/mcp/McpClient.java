package com.deepseek.dsh.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端能力缝 —— 对应原 Harness 的 {@code mcp-client}。
 *
 * <p>桥接外部 MCP（Model Context Protocol）服务器，将其工具注册到 {@code ctx.tools}。
 * 实现（如 stdio MCP 客户端）通过 JSON-RPC 与外部进程通信。
 *
 * <p>设计模式：适配器（将 MCP 协议适配为内部工具体系）+ 桥接。
 */
public interface McpClient {

    /** 连接到一个 MCP 服务器并发现其工具。 */
    List<McpToolDescriptor> connect(String serverCommand, Map<String, String> env) throws Exception;

    /** 调用一个已发现的 MCP 工具。 */
    String callTool(String serverId, String toolName, Map<String, Object> arguments) throws Exception;

    /** 断开某个服务器的连接。 */
    void disconnect(String serverId);
}
