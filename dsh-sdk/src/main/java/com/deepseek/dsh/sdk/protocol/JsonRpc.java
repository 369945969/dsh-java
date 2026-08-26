package com.deepseek.dsh.sdk.protocol;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 消息 —— 对应原 Harness 的 {@code sdk/protocol}。
 *
 * <p>封装请求/响应/通知的构造与解析，是进程外运行时 SDK 的线协议。
 */
public final class JsonRpc {

    private final ObjectMapper mapper = new ObjectMapper();
    private long nextId = 1;

    /** 构造一个请求（有 id）。 */
    public String request(String method, Object params) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", nextId++);
        msg.put("method", method);
        msg.set("params", mapper.valueToTree(params));
        return mapper.writeValueAsString(msg);
    }

    /** 构造一个通知（无 id，无响应）。 */
    public String notification(String method, Object params) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", mapper.valueToTree(params));
        return mapper.writeValueAsString(msg);
    }

    /** 解析一行 JSON-RPC 消息。 */
    public Parsed parse(String line) throws Exception {
        JsonNode node = mapper.readTree(line);
        String method = node.has("method") ? node.get("method").asText() : null;
        JsonNode id = node.has("id") ? node.get("id") : null;
        JsonNode result = node.has("result") ? node.get("result") : null;
        JsonNode error = node.has("error") ? node.get("error") : null;
        return new Parsed(method, id, result, error);
    }

    /** 解析结果。 */
    public record Parsed(
            String method,
            Object id,
            JsonNode result,
            JsonNode errorNode
    ) {
        public boolean isResponse() {
            return method == null && (result != null || errorNode != null);
        }

        public boolean isRequest() {
            return method != null && id != null;
        }

        public boolean isNotification() {
            return method != null && id == null;
        }

        public Optional<String> error() {
            return errorNode != null
                    ? Optional.of(errorNode.path("message").asText("未知错误"))
                    : Optional.empty();
        }
    }
}
