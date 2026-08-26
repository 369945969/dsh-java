package com.deepseek.dsh.sdk.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 行分隔 JSON-RPC 2.0 传输对端 —— 对应原 Harness 的 {@code JsonRpcLineTransport}。
 *
 * <p>在任意 {@link InputStream}/{@link OutputStream} 之上提供双向 JSON-RPC：
 * {@link #request} 携带 id 并等待响应，{@link #notify} 无 id 无响应。
 * 读循环在虚拟线程中排空输入，按 id 匹配待响应 future；对端错误帧转为
 * {@link JsonRpcException} 异常完成。关闭时拒绝全部待响应请求。
 *
 * <p>这是 SDK 的底层协议原语；高层类型化客户端 {@code HarnessClient} 构建于其上。
 *
 * <p>设计模式：远程代理（线协议适配）+ 请求-响应匹配。
 */
public final class JsonRpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final OutputStream out;
    private final BufferedReader reader;
    private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);
    private volatile boolean closed = false;
    private final Thread readerThread;

    public JsonRpcClient(InputStream in, OutputStream out) {
        this.out = out;
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.readerThread = Thread.startVirtualThread(this::readLoop);
    }

    /** 发送一个请求并等待响应（result 节点）；错误帧以 {@link JsonRpcException} 异常完成。 */
    public CompletableFuture<JsonNode> request(String method, Object params) {
        long id = idSeq.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.set("params", mapper.valueToTree(params));
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(String.valueOf(id), future);
        try {
            writeLine(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            pending.remove(String.valueOf(id));
            future.completeExceptionally(e);
        }
        return future;
    }

    /** 发送一个通知（无 id，无响应）。 */
    public void notify(String method, Object params) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.set("params", mapper.valueToTree(params));
        try {
            writeLine(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.debug("notify 写入失败: {}", e.toString());
        }
    }

    /** 底层 ObjectMapper，供高层客户端解析结果字段。 */
    public ObjectMapper mapper() {
        return mapper;
    }

    private synchronized void writeLine(String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                handleLine(line);
            }
        } catch (Exception e) {
            if (!closed) {
                log.debug("JSON-RPC 读循环结束: {}", e.toString());
                failPending(e);
            }
        }
    }

    private void handleLine(String line) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (Exception e) {
            return; // 损坏行忽略
        }
        JsonNode idNode = node.get("id");
        if (idNode == null) return; // 通知（客户端侧忽略）
        CompletableFuture<JsonNode> future = pending.remove(idNode.asText());
        if (future == null) return;
        JsonNode error = node.get("error");
        if (error != null) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("未知错误")));
        } else {
            future.complete(node.get("result"));
        }
    }

    private void failPending(Exception e) {
        for (CompletableFuture<JsonNode> f : pending.values()) f.completeExceptionally(e);
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        failPending(new IOException("JSON-RPC transport closed"));
        readerThread.interrupt();
    }
}
