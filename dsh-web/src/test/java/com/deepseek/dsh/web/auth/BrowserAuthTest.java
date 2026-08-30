package com.deepseek.dsh.web.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BrowserAuth 单元测试 —— 验证 cookie 格式、token 换发、认证裁决
 * 与 harness 0.1.2 browser-auth.ts 逐字节一致。
 */
class BrowserAuthTest {

    @TempDir
    Path tempDir;

    private BrowserAuth createAuth() throws Exception {
        return BrowserAuth.create(tempDir.resolve("browser-session.json"), 30);
    }

    @Test
    void cookieRoundTrip() throws Exception {
        BrowserAuth auth = createAuth();
        String authority = "localhost:8765";
        // 模拟 token 换发
        var verdict = auth.authorizeIndex("GET", "/", Map.of("token", List.of(auth.launchToken())),
                authority, null);
        assertFalse(verdict.serve());
        assertNotNull(verdict.response());
        assertEquals(303, verdict.response().status());
        String setCookie = verdict.response().headers().get("set-cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("dsh-auth-"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Max-Age=2592000"));
    }

    @Test
    void noTokenReturns401() throws Exception {
        BrowserAuth auth = createAuth();
        var verdict = auth.authorizeIndex("GET", "/", Map.of(), "localhost:8765", null);
        assertFalse(verdict.serve());
        assertEquals(401, verdict.response().status());
        assertTrue(verdict.response().body().contains("authentication required"));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        BrowserAuth auth = createAuth();
        var verdict = auth.authorizeIndex("GET", "/", Map.of("token", List.of("invalid")),
                "localhost:8765", null);
        assertFalse(verdict.serve());
        assertEquals(401, verdict.response().status());
    }

    @Test
    void validCookieAuthenticates() throws Exception {
        BrowserAuth auth = createAuth();
        String authority = "localhost:8765";
        // 先换 cookie
        var exchange = auth.authorizeIndex("GET", "/", Map.of("token", List.of(auth.launchToken())),
                authority, null);
        String setCookie = exchange.response().headers().get("set-cookie");
        String cookieName = setCookie.substring(0, setCookie.indexOf('='));
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        String cookieHeader = cookieName + "=" + cookieValue;

        // 用 cookie 访问
        assertTrue(auth.isAuthenticated(authority, cookieHeader));
    }

    @Test
    void tamperedCookieRejected() throws Exception {
        BrowserAuth auth = createAuth();
        String authority = "localhost:8765";
        var exchange = auth.authorizeIndex("GET", "/", Map.of("token", List.of(auth.launchToken())),
                authority, null);
        String setCookie = exchange.response().headers().get("set-cookie");
        String cookieName = setCookie.substring(0, setCookie.indexOf('='));
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        // 篡改签名最后一位
        char lastChar = cookieValue.charAt(cookieValue.length() - 1);
        char tampered = lastChar == 'A' ? 'B' : 'A';
        String tamperedValue = cookieValue.substring(0, cookieValue.length() - 1) + tampered;
        String cookieHeader = cookieName + "=" + tamperedValue;

        assertFalse(auth.isAuthenticated(authority, cookieHeader));
    }

    @Test
    void wrongAuthorityRejected() throws Exception {
        BrowserAuth auth = createAuth();
        String authority = "localhost:8765";
        var exchange = auth.authorizeIndex("GET", "/", Map.of("token", List.of(auth.launchToken())),
                authority, null);
        String setCookie = exchange.response().headers().get("set-cookie");
        String cookieName = setCookie.substring(0, setCookie.indexOf('='));
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        String cookieHeader = cookieName + "=" + cookieValue;

        // 用不同 authority 验证 → 应失败
        assertFalse(auth.isAuthenticated("evil.com:1234", cookieHeader));
    }

    @Test
    void secretPersistsAcrossInstances() throws Exception {
        Path secretFile = tempDir.resolve("browser-session.json");
        BrowserAuth auth1 = BrowserAuth.create(secretFile, 30);
        BrowserAuth auth2 = BrowserAuth.create(secretFile, 30);
        // 两个实例共享同一密钥 → auth1 签发的 cookie 可被 auth2 验证
        String authority = "localhost:8765";
        var exchange = auth1.authorizeIndex("GET", "/", Map.of("token", List.of(auth1.launchToken())),
                authority, null);
        String setCookie = exchange.response().headers().get("set-cookie");
        String cookieName = setCookie.substring(0, setCookie.indexOf('='));
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertTrue(auth2.isAuthenticated(authority, cookieName + "=" + cookieValue));
    }

    @Test
    void authenticatedUrlContainsToken() throws Exception {
        BrowserAuth auth = createAuth();
        String url = auth.authenticatedUrl("localhost", 8765);
        assertTrue(url.contains("?token="));
        assertTrue(url.contains(auth.launchToken()));
    }

    @Test
    void headRequestHasNoBody() throws Exception {
        BrowserAuth auth = createAuth();
        var verdict = auth.authorizeIndex("HEAD", "/", Map.of(), "localhost:8765", null);
        assertFalse(verdict.serve());
        assertEquals(401, verdict.response().status());
        assertTrue(verdict.response().body().isEmpty());
    }
}
