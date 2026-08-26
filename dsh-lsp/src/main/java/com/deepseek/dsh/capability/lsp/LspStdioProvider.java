package com.deepseek.dsh.capability.lsp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * stdio LSP 提供者 —— 对应原 Harness 的 {@code lsp-stdio}。
 *
 * <p>通过子进程 stdin/stdout 以 LSP JSON-RPC 通信。
 * 实现 initialize → textDocument/didOpen → textDocument/definition 等标准流程。
 *
 * <p>设计模式：适配器（LSP 协议 ↔ 内部能力缝）。
 */
public final class LspStdioProvider implements LspCapability {

    private static final Logger log = LoggerFactory.getLogger(LspStdioProvider.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Process> servers = new ConcurrentHashMap<>();
    private final Map<String, BufferedReader> readers = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(0);

    @Override
    public String startServer(String command, String workspaceRoot) {
        String[] cmd = command.split("\\s+");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String serverId = "lsp-" + process.pid();
            servers.put(serverId, process);
            readers.put(serverId, new BufferedReader(new InputStreamReader(process.getInputStream())));

            // 发送 initialize
            sendLsp(process, "initialize", Map.of(
                    "processId", ProcessHandle.current().pid(),
                    "rootUri", "file://" + workspaceRoot,
                    "capabilities", Map.of()));
            readResponse(readers.get(serverId)); // 丢弃 initialize 响应
            sendLsp(process, "initialized", Map.of(), true); // notification
            log.debug("LSP 服务器已启动: {} (id={})", command, serverId);
            return serverId;
        } catch (Exception e) {
            throw new RuntimeException("无法启动 LSP 服务器: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Location> findDefinitions(String serverId, String filePath, int line, int character) {
        Process process = servers.get(serverId);
        BufferedReader reader = readers.get(serverId);
        if (process == null) return List.of();
        try {
            sendLsp(process, "textDocument/definition", Map.of(
                    "textDocument", Map.of("uri", "file://" + filePath),
                    "position", Map.of("line", line, "character", character)));
            JsonNode resp = readResponse(reader);
            List<Location> locations = new ArrayList<>();
            if (resp != null) {
                JsonNode result = resp.path("result");
                if (result.isArray()) {
                    for (JsonNode loc : result) {
                        locations.add(new Location(
                                loc.path("uri").asText("").replace("file://", ""),
                                loc.path("range").path("start").path("line").asInt(),
                                loc.path("range").path("start").path("character").asInt()));
                    }
                }
            }
            return locations;
        } catch (Exception e) {
            log.warn("LSP 查询定义失败: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public List<Diagnostic> diagnostics(String serverId, String filePath) {
        // 简化：LSP 诊断通常以通知推送，此处占位返回空
        return List.of();
    }

    @Override
    public void stopServer(String serverId) {
        Process p = servers.remove(serverId);
        readers.remove(serverId);
        if (p != null) p.destroyForcibly();
    }

    private void sendLsp(Process process, String method, Map<String, Object> params) throws Exception {
        sendLsp(process, method, params, false);
    }

    private void sendLsp(Process process, String method, Map<String, Object> params, boolean notification) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        if (!notification) msg.put("id", idSeq.incrementAndGet());
        msg.put("method", method);
        msg.set("params", mapper.valueToTree(params));
        String json = mapper.writeValueAsString(msg);
        // LSP Content-Length 帧
        byte[] bytes = json.getBytes();
        OutputStream os = process.getOutputStream();
        os.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes());
        os.write(bytes);
        os.flush();
    }

    private JsonNode readResponse(BufferedReader reader) throws Exception {
        // 读取 Content-Length 头
        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        if (contentLength <= 0) return null;
        char[] buf = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(buf, read, contentLength - read);
            if (n < 0) break;
            read += n;
        }
        return mapper.readTree(new String(buf, 0, read));
    }
}
