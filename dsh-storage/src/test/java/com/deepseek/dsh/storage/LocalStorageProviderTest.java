package com.deepseek.dsh.storage;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地存储提供者测试 —— 内存后端 + 文件后端。
 */
class LocalStorageProviderTest {

    @Test
    void 内存后端增删查() {
        var provider = new LocalStorageProvider();
        var backend = provider.getBackend("default").orElseThrow();
        backend.put("k1", "v1");
        assertEquals("v1", backend.get("k1").orElseThrow());
        assertEquals(1, backend.all().size());
        backend.remove("k1");
        assertTrue(backend.get("k1").isEmpty());
    }

    @Test
    void 文件后端持久化(@TempDir Path dir) {
        Path file = dir.resolve("store.json");
        var provider = new LocalStorageProvider(dir);
        var backend = provider.getBackend("file").orElseThrow();
        backend.put("persist", "yes");
        // 重新打开同一文件，验证持久化
        var reloaded = new LocalStorageProvider(dir);
        var again = reloaded.getBackend("file").orElseThrow();
        assertEquals("yes", again.get("persist").orElseThrow());
    }

    @Test
    void 重复注册后端抛异常() {
        var provider = new LocalStorageProvider();
        assertThrows(IllegalStateException.class,
                () -> provider.registerBackend("default", new LocalStorageProvider.MemoryBackend()));
    }
}
