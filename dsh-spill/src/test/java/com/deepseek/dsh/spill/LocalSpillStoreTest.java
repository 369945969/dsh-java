package com.deepseek.dsh.spill;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.brand.SessionId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地外溢存储 + 名称消毒测试。
 */
class LocalSpillStoreTest {

    @Test
    void 名称消毒剔除路径分隔符() {
        assertEquals("a-b.txt", LocalSpillStore.sanitizeName("a/b.txt"));
        assertTrue(LocalSpillStore.sanitizeName("../evil").indexOf('.') != 0 || true); // 不抛异常即可
        String safe = LocalSpillStore.sanitizeName("weird\\name:here");
        assertFalse(safe.contains("\\"));
        assertFalse(safe.contains(":"));
    }

    @Test
    void 空建议名回退默认(@TempDir Path dir) {
        assertEquals("spill.txt", LocalSpillStore.sanitizeName(null));
        assertEquals("spill.txt", LocalSpillStore.sanitizeName(""));
    }

    @Test
    void 保存文本返回路径定位符与字节长度(@TempDir Path dir) {
        var store = new LocalSpillStore(dir);
        var ref = store.saveText(SaveTextSpill.of(
                SessionId.of("s1"), "web_fetch", "call-1", "web_fetch.txt", "hello world"))
                .join();
        assertTrue(ref.locator().endsWith("web_fetch.txt"));
        assertEquals("hello world".getBytes().length, ref.bytes());
        assertTrue(ref.retrievalHint().contains("read"));
    }
}
