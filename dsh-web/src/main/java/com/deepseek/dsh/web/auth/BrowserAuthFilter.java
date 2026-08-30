package com.deepseek.dsh.web.auth;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 浏览器令牌认证过滤器 —— 移植 harness 0.1.2 的
 * {@code client-connection} 挂载语义（api-request-trust + BrowserAuth）。
 *
 * <p>门禁范围：
 * <ul>
 *   <li>{@code /}、{@code /index.html}、{@code /preview.html}：启动令牌换 cookie 或 401；</li>
 *   <li>{@code /api/**}：Host trust fence（DNS rebinding / 跨站防御）→ 403，
 *       浏览器会话 cookie 认证 → 401，然后才进入 RPC 分发；</li>
 *   <li>{@code /ws/**}：同样要求会话 cookie（Java 原生通道）。</li>
 * </ul>
 *
 * <p>静态资源（/assets、/plugins、favicon 等）保持公开，与 harness 中
 * frontend-static 仅对索引页认证、client-modules 的 /plugins 组合路由不鉴权一致。
 */
@Component
public class BrowserAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BrowserAuthFilter.class);

    private final BrowserAuth auth;
    private final Set<String> trustedHosts;

    public BrowserAuthFilter(
            BrowserAuth auth,
            @Value("${dsh.auth.trusted-hosts:}") String trustedHostsCsv) {
        this.auth = auth;
        this.trustedHosts = new HashSet<>();
        for (String entry : trustedHostsCsv.split(",")) {
            String host = entry.trim().toLowerCase();
            if (!host.isEmpty()) this.trustedHosts.add(host);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null) path = "/";
        if (isIndexRoute(path)) {
            var verdict = auth.authorizeIndex(
                    request.getMethod(), path, parseQuery(request.getQueryString()),
                    request.getHeader("Host"), request.getHeader("Cookie"));
            if (!verdict.serve()) {
                write(verdict.response(), response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            if (!isTrustedApiRequest(request)) {
                response.setStatus(403);
                response.getWriter().write("forbidden");
                return;
            }
            if (!auth.isAuthenticated(request.getHeader("Host"), request.getHeader("Cookie"))) {
                response.setStatus(401);
                response.getWriter().write("unauthorized");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (path.equals("/ws") || path.startsWith("/ws/")) {
            if (!auth.isAuthenticated(request.getHeader("Host"), request.getHeader("Cookie"))) {
                response.setStatus(401);
                response.getWriter().write("unauthorized");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isIndexRoute(String path) {
        return path.equals("/") || path.equals("/index.html") || path.equals("/preview.html");
    }

    /** 从原始 query string 解析多值参数；绝不用 getParameterMap（避免吞掉 form POST 请求体）。 */
    static Map<String, List<String>> parseQuery(String queryString) {
        Map<String, List<String>> params = new java.util.LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) return params;
        for (String pair : queryString.split("&")) {
            int at = pair.indexOf('=');
            String name = at == -1 ? pair : pair.substring(0, at);
            String value = at == -1 ? "" : pair.substring(at + 1);
            params.computeIfAbsent(java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8),
                    k -> new java.util.ArrayList<>())
                    .add(java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
        return params;
    }

    private void write(BrowserAuth.AuthResponse resp, HttpServletResponse response) throws IOException {
        response.setStatus(resp.status());
        resp.headers().forEach(response::setHeader);
        if (!resp.body().isEmpty()) response.getWriter().write(resp.body());
    }

    /**
     * /api 的 Host/Origin trust fence（对应 api-request-trust.isTrustedApiRequest）。
     * loopback 或 trustedHosts 声明的 authority 方可通过：带显式端口的条目精确匹配，
     * 无端口的条目匹配该主机名任意端口。
     */
    private boolean isTrustedApiRequest(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null) return false;
        URI uri;
        try {
            uri = URI.create("http://" + host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String hostname = uri.getHost();
        if (hostname == null) return false;
        if (!isLoopbackHostname(hostname) && !matchesTrustedAuthority(hostname, uri.getPort() < 0 ? null : String.valueOf(uri.getPort()))) {
            return false;
        }
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            URI originUri = URI.create(origin);
            String originHost = originUri.getHost() == null ? null
                    : (originUri.getPort() > 0 ? originUri.getHost().toLowerCase() + ":" + originUri.getPort()
                            : originUri.getHost().toLowerCase());
            String requestHost = uri.getAuthority();
            return originHost != null && originHost.equalsIgnoreCase(
                    (hostname + (uri.getPort() > 0 ? ":" + uri.getPort() : "")).toLowerCase())
                    || (requestHost != null && originHost.equalsIgnoreCase(requestHost.toLowerCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean matchesTrustedAuthority(String hostname, String port) {
        if (port == null) return trustedHosts.contains(hostname);
        return trustedHosts.contains(hostname) || trustedHosts.contains(hostname + ":" + port);
    }

    /** 对应 loopback-hostname.isLoopbackHostname：localhost、[::1]、任意 127/8。 */
    static boolean isLoopbackHostname(String hostname) {
        if (hostname.equals("localhost") || hostname.equals("[::1]") || hostname.equals("::1")) return true;
        String bare = hostname.startsWith("[") && hostname.endsWith("]")
                ? hostname.substring(1, hostname.length() - 1) : hostname;
        if (!bare.equals(hostname)) return bare.equals("::1");
        String[] parts = hostname.split("\\.");
        if (parts.length != 4 || !parts[0].equals("127")) return false;
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
