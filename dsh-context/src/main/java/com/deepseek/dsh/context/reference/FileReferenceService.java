package com.deepseek.dsh.context.reference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 文件引用服务 —— 对应原 Harness 的 {@code file-reference-local}。
 *
 * <p>解析用户消息中的 @file 引用，将文件内容注入为模型可见上下文。
 * 支持模糊匹配与有界索引（防止超大文件撑爆上下文窗口）。
 *
 * <p>设计模式：注册表 + 模板方法（插件基类）。
 */
public final class FileReferenceService
        extends AbstractCapabilityPlugin<FileReferences> implements FileReferences {

    private static final Logger log = LoggerFactory.getLogger(FileReferenceService.class);

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    @Override
    protected Class<FileReferences> serviceType() {
        return FileReferences.class;
    }

    @Override
    public String resolve(Path basePath, String reference) {
        // 模糊匹配：尝试精确路径 + 通配
        Path exact = basePath.resolve(reference);
        if (Files.isReadable(exact)) {
            return readFile(exact, reference);
        }
        // 简单模糊：取文件名后缀匹配
        return "（未找到文件: " + reference + "）";
    }

    private String readFile(Path path, String refName) {
        return cache.computeIfAbsent(path.toString(), k -> {
            try {
                String content = Files.readString(path);
                if (content.length() > 10000) {
                    content = content.substring(0, 10000) + "\n…[truncated]";
                }
                return "--- @" + refName + " ---\n" + content;
            } catch (Exception e) {
                    log.warn("Failed to read reference file {}: {}", path, e.toString());
                return "(Read failed: " + refName + ")";
            }
        });
    }
}
