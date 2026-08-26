package com.deepseek.dsh.context.reference;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件引用服务测试 —— 解析已有文件/截断超大文件/未找到/缓存。
 */
class FileReferenceServiceTest {

    @Test
    void 解析已存在的文件并注入头(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("note.txt");
        Files.writeString(f, "hello world");
        var svc = new FileReferenceService();
        String out = svc.resolve(dir, "note.txt");
        assertTrue(out.startsWith("--- @note.txt ---"));
        assertTrue(out.contains("hello world"));
    }

    @Test
    void 超大文件被截断(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("big.txt");
        Files.writeString(f, "x".repeat(20000));
        var svc = new FileReferenceService();
        String out = svc.resolve(dir, "big.txt");
        assertTrue(out.contains("已截断"));
        // 截断后内容上限 + 头部不超过约 10000 + 头
        assertTrue(out.length() < 11000);
    }

    @Test
    void 未找到文件返回提示(@TempDir Path dir) {
        var svc = new FileReferenceService();
        String out = svc.resolve(dir, "missing.md");
        assertTrue(out.contains("未找到文件"));
        assertTrue(out.contains("missing.md"));
    }

    @Test
    void 同路径缓存命中(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cached.txt");
        Files.writeString(f, "v1");
        var svc = new FileReferenceService();
        String first = svc.resolve(dir, "cached.txt");
        // 修改底层文件，缓存应仍返回旧内容（computeIfAbsent 命中）
        Files.writeString(f, "v2-changed");
        String second = svc.resolve(dir, "cached.txt");
        assertEquals(first, second);
        assertTrue(second.contains("v1"));
    }
}
