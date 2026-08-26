package com.deepseek.dsh.credentials;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地凭据提供者 + 授权服务测试。
 */
class CredentialsTest {

    @Test
    void 从env文件加载凭据(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        java.nio.file.Files.writeString(env, """
                # 注释行
                DEEPSEEK_API_KEY=sk-abc123
                EMPTY=
                WITH_QUOTES="quoted-value"
                """);
        var p = new LocalCredentialsProvider(env);
        assertEquals("sk-abc123", p.get("DEEPSEEK_API_KEY").orElseThrow());
        assertEquals("quoted-value", p.get("WITH_QUOTES").orElseThrow());
        // 进程环境变量叠加（应包含 PATH 等）
        assertNotNull(p.references());
        assertTrue(p.references().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void 注册与查询运行时凭据() {
        var p = new LocalCredentialsProvider();
        p.register("MY_TOKEN", "xyz");
        assertEquals("xyz", p.get("MY_TOKEN").orElseThrow());
    }

    @Test
    void 不存在的env文件不抛异常() {
        var p = new LocalCredentialsProvider(Path.of("/no/such/.env"));
        // 仍叠加进程环境变量
        assertFalse(p.references().isEmpty());
    }

    @Test
    void 授权流程返回结果() throws Exception {
        var svc = new AuthorizationService();
        String r = svc.authorize("OAUTH", ref -> "token-" + ref).join();
        assertEquals("token-OAUTH", r);
        // flow 返回 null → 失败提示
        String fail = svc.authorize("X", ref -> null).join();
        assertTrue(fail.contains("授权失败"));
    }
}
