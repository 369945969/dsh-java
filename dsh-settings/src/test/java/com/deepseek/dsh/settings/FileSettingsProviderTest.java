package com.deepseek.dsh.settings;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件设置提供者测试 —— 命名空间 KV 持久化往返。
 */
class FileSettingsProviderTest {

    @Test
    void 增删查与持久化往返(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        var p = new FileSettingsProvider(file);
        p.set("ui", "theme", "dark");
        p.set("ui", "lang", "zh");
        assertEquals("dark", p.get("ui", "theme").orElseThrow());
        assertEquals("zh", p.get("ui", "lang").orElseThrow());
        assertEquals(2, p.getAll("ui").size());

        // 重新加载同一文件验证持久化
        var reloaded = new FileSettingsProvider(file);
        assertEquals("dark", reloaded.get("ui", "theme").orElseThrow());
        assertEquals("zh", reloaded.get("ui", "lang").orElseThrow());
    }

    @Test
    void 命名空间隔离(@TempDir Path dir) {
        var p = new FileSettingsProvider(dir.resolve("s.json"));
        p.set("ns1", "k", "v1");
        p.set("ns2", "k", "v2");
        assertEquals("v1", p.get("ns1", "k").orElseThrow());
        assertEquals("v2", p.get("ns2", "k").orElseThrow());
        assertTrue(p.get("ns3", "k").isEmpty());
    }
}
