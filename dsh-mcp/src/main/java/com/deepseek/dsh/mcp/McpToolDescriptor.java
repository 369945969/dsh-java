package com.deepseek.dsh.mcp;

import java.util.Map;

/**
 * MCP 工具描述 —— 从外部 MCP 服务器发现的工具。
 */
public record McpToolDescriptor(
        /** 工具名（MCP 服务器命名空间内）。 */
        String name,
        /** 描述。 */
        String description,
        /** 参数 JSON Schema。 */
        Map<String, Object> inputSchema
) {}
