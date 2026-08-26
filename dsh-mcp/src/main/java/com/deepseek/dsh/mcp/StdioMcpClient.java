package com.deepseek.dsh.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * Stdio MCP 客户端 —— 通过子进程的 stdin/stdout 以 JSON-RPC 通信。
 *
 * <p>对应原 Harness 的 {@code mcp-client} stdio 传输。简化实现：
 * 启动子进程，发送 {@code tools/list}，注册发现的工具为内部 {@link ToolSchema}。
 *
 * <p>设计模式：适配器（MCP ↔ 内部工具）。
 */
public final class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);

    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<McpToolDescriptor>> discovered = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public List<McpToolDescriptor> connect(String serverCommand, Map<String, String> env) throws Exception {
        String[] cmd = serverCommand.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (env != null) pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        String serverId = "mcp-" + process.pid();
        processes.put(serverId, process);

        // 简化：发送 tools/list 请求并解析响应
        // 真实实现需完整的 JSON-RPC 握手（initialize → tools/list）
        List<McpToolDescriptor> tools = new ArrayList<>();
        try {
            var req = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
            String json = mapper.writeValueAsString(req);
            process.getOutputStream().write((json + "\n").getBytes());
            process.getOutputStream().flush();

            // 读取响应（简化：阻塞读取一行）
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null) {
                var resp = mapper.readTree(line);
                var arr = resp.path("result").path("tools");
                if (arr.isArray()) {
                    for (var t : arr) {
                        tools.add(new McpToolDescriptor(
                                t.path("name").asText(),
                                t.path("description").asText(""),
                                mapper.convertValue(t.path("inputSchema"), Map.class)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP 连接失败 ({}): {}", serverCommand, e.toString());
        }
        discovered.put(serverId, tools);
        return tools;
    }

    @Override
    public String callTool(String serverId, String toolName, Map<String, Object> arguments) throws Exception {
        Process process = processes.get(serverId);
        if (process == null) throw new IllegalStateException("MCP 服务器未连接: " + serverId);
        var req = Map.of("jsonrpc", "2.0", "id", System.currentTimeMillis(),
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments));
        String json = mapper.writeValueAsString(req);
        process.getOutputStream().write((json + "\n").getBytes());
        process.getOutputStream().flush();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
        String line = reader.readLine();
        if (line == null) return "（MCP 无响应）";
        var resp = mapper.readTree(line);
        return resp.path("result").path("content").path(0).path("text").asText("（无文本结果）");
    }

    @Override
    public void disconnect(String serverId) {
        Process p = processes.remove(serverId);
        discovered.remove(serverId);
        if (p != null) p.destroyForcibly();
    }

    /** 将 MCP 工具描述转换为内部 ToolSchema。 */
    public static ToolSchema toSchema(McpToolDescriptor desc) {
        return new ToolSchema(desc.name(), desc.description(), desc.inputSchema());
    }
}
