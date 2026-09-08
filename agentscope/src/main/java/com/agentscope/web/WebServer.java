package com.agentscope.web;

import com.agentscope.config.Config;
import com.agentscope.ws.WsServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class WebServer {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpServer server;
    private final Config cfg;

    public WebServer(int port, HarnessAgent agent, Config cfg) throws IOException {
        this.cfg = cfg;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        // 静态文件（index.html / app.js）
        server.createContext("/", new StaticHandler());

        // API
        server.createContext("/api/agent/health", new HealthHandler());
        server.createContext("/api/agent/send", new SendHandler(agent, cfg));
        server.createContext("/api/sessions", new SessionListHandler(agent, cfg));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() { server.start(); }

    // ---- token 校验 ----
    static boolean checkToken(HttpExchange ex, Config cfg) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null && cookie.contains("dsh-auth=" + cfg.token)) return true;
        String q = ex.getRequestURI().getQuery();
        return q != null && q.contains("token=" + cfg.token);
    }

    static boolean unauthorized(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(401, 0); ex.getResponseBody().close();
        return false;
    }

    // ---- 静态文件 ----
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();

            // token 握手：URL 带 ?token= 且匹配配置 → 设 cookie（后续 API/WS 复用）
            if (query != null && query.contains("token=")) {
                String[] parts = query.split("token=");
                if (parts.length > 1) {
                    String t = parts[1].split("&")[0];
                    ex.getResponseHeaders().set("Set-Cookie", "dsh-auth=" + t + "; Path=/; HttpOnly");
                }
            }

            if (path.equals("/") || path.equals("/index.html")) path = "/index.html";
            Path resDir = Path.of(System.getProperty("user.dir"), "src/main/resources/static");
            Path file = resDir.resolve(path.substring(1));
            if (!Files.exists(file)) {
                file = Path.of(System.getProperty("user.dir"), "agentscope/src/main/resources/static" + path);
            }
            if (!Files.exists(file)) { ex.sendResponseHeaders(404, 0); ex.getResponseBody().close(); return; }
            byte[] body = Files.readAllBytes(file);
            String ct = path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, body.length);
            OutputStream os = ex.getResponseBody(); os.write(body); os.close();
        }
    }

    // ---- health ----
    static class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { if (!unauthorized(ex)) {} return; }
            byte[] b = "{\"status\":\"ok\"}".getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            OutputStream os = ex.getResponseBody(); os.write(b); os.close();
        }
    }

    // ---- send（一次性对话，阻塞）----
    static class SendHandler implements HttpHandler {
        private final HarnessAgent agent;
        private final Config cfg;
        SendHandler(HarnessAgent a, Config c) { agent = a; cfg = c; }

        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, cfg)) { unauthorized(ex); return; }
            String body = readBody(ex);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = M.readValue(body, Map.class);
                String message = (String) req.getOrDefault("message", "");
                String sessionId = (String) req.getOrDefault("sessionId", UUID.randomUUID().toString());
                String userId = (String) req.getOrDefault("userId", "default");

                RuntimeContext ctx = RuntimeContext.builder()
                        .sessionId(sessionId).userId(userId).build();
                Object result = agent.call(new UserMessage(message), ctx).block();

                // 从 AssistantMessage（extends Msg）提取文本内容
                String reply = "";
                long tokens = 0;
                if (result instanceof io.agentscope.core.message.Msg msg) {
                    reply = msg.getTextContent();
                    if (msg.getUsage() != null) {
                        // ChatUsage 有 totalTokens 等字段
                        try {
                            var u = msg.getUsage();
                            // 反射取 totalTokens（不同版本字段名可能不同）
                            for (var f : u.getClass().getMethods()) {
                                if (f.getName().contains("total") && f.getParameterCount() == 0) {
                                    tokens = ((Number) f.invoke(u)).longValue();
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("sessionId", sessionId);
                resp.put("reply", reply != null ? reply : "");
                resp.put("status", "ok");
                resp.put("totalTokens", tokens);
                byte[] b = M.writeValueAsBytes(resp);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                OutputStream os = ex.getResponseBody(); os.write(b); os.close();
            } catch (Exception e) {
                byte[] b = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes();
                ex.sendResponseHeaders(500, b.length);
                OutputStream os = ex.getResponseBody(); os.write(b); os.close();
            }
        }
    }

    // ---- session list（从 workspace 目录扫描）----
    static class SessionListHandler implements HttpHandler {
        private final Config cfg;
        SessionListHandler(HarnessAgent a, Config c) { cfg = c; }
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, cfg)) { unauthorized(ex); return; }
            List<Map<String, String>> items = new ArrayList<>();
            Path sessDir = Path.of(cfg.workspaceDir, "agents", "agentscope-bot", "sessions");
            if (Files.exists(sessDir)) {
                try (var stream = Files.list(sessDir)) {
                    stream.filter(Files::isDirectory).forEach(d -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("sessionId", d.getFileName().toString());
                        items.add(m);
                    });
                } catch (IOException ignored) {}
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("items", items);
            r.put("count", items.size());
            byte[] b = M.writeValueAsBytes(r);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            OutputStream os = ex.getResponseBody(); os.write(b); os.close();
        }
    }

    static String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        return new String(is.readAllBytes());
    }
}
