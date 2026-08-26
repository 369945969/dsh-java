package com.deepseek.dsh.sdk.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 分发器 —— ACP 与 SDK 服务端的共用基座。
 *
 * <p>消除 {@code AcpServer} 与 {@code JsonRpcAgentServer} 中逐字节重复的
 * {@code writeResult} / {@code writeError} / 读取循环 / 解析逻辑。
 *
 * <p>核心改进（设计模式升级）：
 * <ul>
 *   <li><b>命令注册</b>（替代 switch 硬编码）—— 通过 {@link #register} 注册
 *       method → {@link RpcHandler} 映射，新方法只需注册一行，无需改分发逻辑。</li>
 *   <li><b>统一异常处理</b> —— 分发器捕获 handler 异常并转为 JSON-RPC error 响应，
 *       区分业务错误（-32603）与协议错误（-32601/-32700）。</li>
 *   <li><b>try-with-resources</b> —— 确保 IO 资源释放。</li>
 * </ul>
 *
 * <p>设计模式：命令（每个 handler 是一个命令）+ 前端控制器（单一入口分发）。
 */
public final class JsonRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcDispatcher.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, RpcHandler> handlers = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(0);

    /** 注册一个方法处理器。 */
    public void register(String method, RpcHandler handler) {
        handlers.put(method, handler);
    }

    /** 在给定流上运行 newline-delimited JSON-RPC 循环。 */
    public void runLoop(InputStream in, OutputStream out) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                dispatch(line, out);
            }
        }
    }

    private void dispatch(String line, OutputStream out) throws IOException {
        JsonNode req;
        try {
            req = mapper.readTree(line);
        } catch (Exception e) {
            writeError(out, null, -32700, "解析错误: " + e.getMessage());
            return;
        }
        String method = req.path("method").asText("");
        JsonNode id = req.path("id");
        JsonNode params = req.path("params");

        RpcHandler handler = handlers.get(method);
        if (handler == null) {
            writeError(out, id, -32601, "未知方法: " + method);
            return;
        }
        try {
            JsonNode result = handler.handle(params, ctx(mapper, id, idSeq));
            writeResult(out, id, result);
        } catch (Exception e) {
            log.warn("[JSON-RPC] 方法 {} 处理失败: {}", method, e.toString());
            writeError(out, id, -32603, "内部错误: " + e.getMessage());
        }
    }

    /** 方法处理器函数式接口。 */
    @FunctionalInterface
    public interface RpcHandler {
        /**
         * @param params 请求参数节点
         * @param ctx    分发上下文（提供 mapper、id 等）
         * @return 结果节点（放入响应 result 字段）
         */
        JsonNode handle(JsonNode params, RpcContext ctx) throws Exception;
    }

    /** 分发上下文 —— handler 可访问的运行时工具。 */
    public record RpcContext(ObjectMapper mapper, JsonNode id, AtomicInteger idSeq) {}

    private static RpcContext ctx(ObjectMapper mapper, JsonNode id, AtomicInteger idSeq) {
        return new RpcContext(mapper, id, idSeq);
    }

    // ---- 响应写入（共用，不再重复） ----------------------------------------

    private void writeResult(OutputStream out, JsonNode id, JsonNode result) throws IOException {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null && !id.isMissingNode()) resp.set("id", id);
        if (result != null) resp.set("result", result);
        out.write((mapper.writeValueAsString(resp) + "\n").getBytes());
        out.flush();
    }

    private void writeError(OutputStream out, JsonNode id, int code, String message) throws IOException {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null && !id.isMissingNode()) resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        out.write((mapper.writeValueAsString(resp) + "\n").getBytes());
        out.flush();
    }
}
