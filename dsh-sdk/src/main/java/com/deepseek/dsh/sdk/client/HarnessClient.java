package com.deepseek.dsh.sdk.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.sdk.protocol.JsonRpc;

/**
 * JSON-RPC 客户端 —— 对应原 Harness 的 {@code sdk/client}。
 *
 * <p>通过子进程的 stdin/stdout 与 dsh-jsonrpc-agent 运行时通信，
 * 提供高级 turns API：创建会话、发送消息、获取结果。
 *
 * <p>设计模式：远程代理（Remote Proxy）+ 请求-响应匹配。
 */
public final class HarnessClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessClient.class);

    private final JsonRpc rpc = new JsonRpc();
    private final Process process;
    private final OutputStream stdin;
    private final BufferedReader stdout;
    private final ConcurrentMap<Object, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public HarnessClient(String runtimeCommand) throws Exception {
        String[] cmd = runtimeCommand.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.stdin = process.getOutputStream();
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        // 后台读取响应线程
        Thread reader = new Thread(this::readLoop, "jsonrpc-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 创建会话，返回会话 ID。 */
    public CompletableFuture<String> createSession() {
        return send("session.create", java.util.Map.of());
    }

    /** 向会话发送消息，返回 agent 回复。 */
    public CompletableFuture<String> run(String sessionId, String message) {
        return send("session.run", java.util.Map.of("sessionId", sessionId, "message", message));
    }

    private CompletableFuture<String> send(String method, Object params) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            String json = rpc.request(method, params);
            // 从 json 提取 id 以匹配响应
            var parsed = rpc.parse(json);
            pending.put(parsed.id(), future);
            stdin.write((json + "\n").getBytes());
            stdin.flush();
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                var parsed = rpc.parse(line);
                if (parsed.isResponse()) {
                    CompletableFuture<String> f = pending.remove(parsed.id());
                    if (f != null) {
                        if (parsed.error().isPresent()) {
                            f.completeExceptionally(new RuntimeException(parsed.error().get()));
                        } else {
                            f.complete(parsed.result() != null ? parsed.result().toString() : "");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("JSON-RPC 读取结束: {}", e.toString());
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
