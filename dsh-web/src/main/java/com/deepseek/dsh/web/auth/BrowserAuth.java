package com.deepseek.dsh.web.auth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 浏览器会话认证 —— 移植自 harness {@code packages/client/connection/src/browser-auth.ts}。
 *
 * <p>启动令牌（进程级，随机 32B base64url）只出现在启动 URL 的 {@code ?token=} 上；
 * GET {@code /} 携带有效令牌时 303 换发持久签名 cookie，此后所有 /api 与索引请求凭
 * cookie 认证。cookie 与 TS 原版逐字节同格式：
 * 名字 {@code dsh-auth-<b64url(sha256(authority))>}，值
 * {@code v1.<b64url(JSON payload)>.<b64url(HMAC-SHA256(secret, body))>}。
 *
 * <p>签名密钥持久化在数据目录 {@code browser-session.json}（对应 TS 的
 * credential 记录 {@code client-connection/browser-session}，kind=grant）。
 */
public final class BrowserAuth {

    /** 与 TS 一致的天毫秒。 */
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final int SECRET_BYTES = 32;
    private static final String TOKEN_QUERY = "token";
    private static final String COOKIE_PREFIX = "dsh-auth-";
    private static final int COOKIE_PAYLOAD_VERSION = 1;
    private static final int STORED_SECRET_VERSION = 1;
    private static final DateTimeFormatter UTC_RFC1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final byte[] secret;
    private final String launchToken;
    private final long maxAgeMillis;
    private final ObjectMapper mapper = new ObjectMapper();

    private BrowserAuth(byte[] secret, String launchToken, long maxAgeMillis) {
        this.secret = secret;
        this.launchToken = launchToken;
        this.maxAgeMillis = maxAgeMillis;
    }

    /**
     * 初始化认证器：加载或首建签名密钥，生成本进程的启动令牌。
     *
     * @param secretFile  密钥文件（数据目录下 browser-session.json）
     * @param maxAgeDays  cookie 绝对寿命（天），对应 TS Config.cookieMaxAgeDays 默认 30
     */
    public static BrowserAuth create(Path secretFile, int maxAgeDays) throws IOException {
        byte[] secret = loadOrCreateSecret(secretFile);
        byte[] tokenBytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(tokenBytes);
        return new BrowserAuth(secret, encodeBase64Url(tokenBytes), (long) maxAgeDays * DAY_MILLIS);
    }

    /** 带启动令牌的应用根 URL（对应 TS authenticatedUrl）。 */
    public String authenticatedUrl(String host, int port) {
        return "http://" + host + ":" + port + "/?" + TOKEN_QUERY + "=" + launchToken;
    }

    public String launchToken() {
        return launchToken;
    }

    /**
     * 认证索引请求（对应 TS authorizeIndex）。
     *
     * @return 裁决；deny 时调用方必须原样写出响应并终止链
     */
    public IndexVerdict authorizeIndex(
            String method, String path, Map<String, List<String>> queryParams, String hostHeader, String cookieHeader) {
        List<String> tokens = queryParams.getOrDefault(TOKEN_QUERY, List.of());
        if (tokens.isEmpty()) {
            return isAuthenticated(hostHeader, cookieHeader)
                    ? IndexVerdict.allow()
                    : IndexVerdict.deny(unauthorized(method));
        }
        String authority = requestAuthority(hostHeader);
        if ("GET".equals(method) && "/".equals(path) && tokens.size() == 1
                && authority != null && tokenMatches(tokens.get(0))) {
            long issuedAt = System.currentTimeMillis();
            long expiresAt = issuedAt + maxAgeMillis;
            String value = encodeCookie(authority, issuedAt, expiresAt);
            return IndexVerdict.deny(new AuthResponse(303, Map.of(
                    "cache-control", "no-store",
                    "location", "/",
                    "referrer-policy", "no-referrer",
                    "set-cookie", sessionCookie(cookieName(authority), value, expiresAt))));
        }
        if ("GET".equals(method) && "/".equals(path) && isAuthenticated(hostHeader, cookieHeader)) {
            return IndexVerdict.deny(new AuthResponse(303, Map.of(
                    "cache-control", "no-store",
                    "location", "/",
                    "referrer-policy", "no-referrer")));
        }
        return IndexVerdict.deny(unauthorized(method));
    }

    /** 索引 401 响应（对应 TS writeUnauthorized）。 */
    private static AuthResponse unauthorized(String method) {
        Map<String, String> headers = Map.of(
                "cache-control", "no-store",
                "content-type", "text/plain; charset=utf-8");
        String body = "HEAD".equals(method) ? "" : "dsh web authentication required; reopen the URL printed by dsh web.\n";
        return new AuthResponse(401, headers, body);
    }

    /** /api 前缀的连接认证（对应 TS requestRejection 的鉴权半边）。 */
    public boolean isAuthenticated(String hostHeader, String cookieHeader) {
        String authority = requestAuthority(hostHeader);
        if (authority == null || cookieHeader == null) return false;
        String value = cookieValue(cookieHeader, cookieName(authority));
        if (value == null) return false;
        CookiePayload payload = decodeCookie(value);
        if (payload == null || !authority.equals(payload.authority)) return false;
        long now = System.currentTimeMillis();
        return payload.issuedAt <= now
                && payload.expiresAt > now
                && payload.expiresAt > payload.issuedAt
                && payload.expiresAt - payload.issuedAt <= maxAgeMillis;
    }

    // --- 逐字节一致的编解码（TS 语义） ---

    /** Host header 的规范 authority（hostname 小写、去默认端口），不可解析返回 null。 */
    static String requestAuthority(String hostHeader) {
        if (hostHeader == null) return null;
        try {
            URI uri = URI.create("http://" + hostHeader);
            return uri.getHost() == null ? null : uri.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeBase64Url(String value) {
        if (value.isEmpty() || value.length() % 4 == 1) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return null;
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean tokenMatches(String actual) {
        byte[] a = actual.getBytes(StandardCharsets.UTF_8);
        byte[] b = launchToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private String cookieName(String authority) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return COOKIE_PREFIX + encodeBase64Url(sha.digest(authority.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String encodeCookie(String authority, long issuedAt, long expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", COOKIE_PAYLOAD_VERSION);
        payload.put("authority", authority);
        payload.put("issuedAt", issuedAt);
        payload.put("expiresAt", expiresAt);
        String body;
        try {
            body = encodeBase64Url(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return "v1." + body + "." + encodeBase64Url(hmac(body));
    }

    private CookiePayload decodeCookie(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 3 || !"v1".equals(parts[0])) return null;
        byte[] actualSig = decodeBase64Url(parts[2]);
        byte[] expectedSig = hmac(parts[1]);
        if (actualSig == null || !MessageDigest.isEqual(actualSig, expectedSig)) return null;
        try {
            byte[] bodyBytes = decodeBase64Url(parts[1]);
            if (bodyBytes == null) return null;
            JsonNode node = mapper.readTree(bodyBytes);
            if (node.path("version").asInt(-1) != COOKIE_PAYLOAD_VERSION
                    || !node.path("authority").isTextual()
                    || !node.path("issuedAt").canConvertToLong()
                    || !node.path("expiresAt").canConvertToLong()) return null;
            return new CookiePayload(node.path("authority").asText(),
                    node.path("issuedAt").asLong(), node.path("expiresAt").asLong());
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cookieValue(String headerValue, String name) {
        for (String segment : headerValue.split(";")) {
            int at = segment.indexOf('=');
            if (at == -1 || !segment.substring(0, at).trim().equals(name)) continue;
            return segment.substring(at + 1).trim();
        }
        return null;
    }

    private String sessionCookie(String name, String value, long expiresAt) {
        return name + "=" + value
                + "; Max-Age=" + (maxAgeMillis / 1000)
                + "; Path=/"
                + "; Expires=" + UTC_RFC1123.format(Instant.ofEpochMilli(expiresAt))
                + "; HttpOnly; SameSite=Strict";
    }

    /** 加载密钥；不存在则生成并以 0600 持久化。 */
    private static byte[] loadOrCreateSecret(Path file) throws IOException {
        if (Files.exists(file)) {
            JsonNode node = new ObjectMapper().readTree(Files.readAllBytes(file));
            if (node.path("version").asInt(-1) != STORED_SECRET_VERSION) {
                throw new IOException("browser-session credential record has an unsupported format");
            }
            byte[] secret = decodeBase64Url(node.path("secret").asText(""));
            if (secret == null || secret.length != SECRET_BYTES) {
                throw new IOException("browser-session credential record has an invalid secret");
            }
            return secret;
        }
        byte[] created = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(created);
        Files.createDirectories(file.getParent());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("version", STORED_SECRET_VERSION);
        record.put("secret", encodeBase64Url(created));
        try {
            Files.setPosixFilePermissions(file.getParent(), EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（Windows）：跳过权限收紧
        }
        Files.writeString(file, new ObjectMapper().writeValueAsString(record),
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX：跳过
        }
        return created;
    }

    /** 索引认证裁决：serve 表示放行链路，deny 携带既定响应。 */
    public record IndexVerdict(boolean serve, AuthResponse response) {
        public static IndexVerdict allow() { return new IndexVerdict(true, null); }
        public static IndexVerdict deny(AuthResponse response) { return new IndexVerdict(false, response); }
    }

    /** 待写出的 HTTP 响应（状态、头、体）。 */
    public record AuthResponse(int status, Map<String, String> headers, String body) {
        AuthResponse(int status, Map<String, String> headers) { this(status, headers, ""); }
    }

    private record CookiePayload(String authority, long issuedAt, long expiresAt) {}
}
