package com.deepseek.dsh.capability.fs.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.deepseek.dsh.capability.fs.FsCapability;

/**
 * 本地文件系统提供者 —— 对应原 Harness 的 {@code fs-local}。
 *
 * <p>所有操作均作用于本地文件系统。
 * <p>设计模式：策略的具体实现。
 */
public final class FsLocalProvider implements FsCapability {

    private static final int DEFAULT_LIMIT = 2000;

    @Override
    public String read(Path path, int offset, int limit) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = Math.max(0, offset);
        int to = limit <= 0 ? lines.size() : Math.min(lines.size(), from + limit);
        if (from >= lines.size()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines.get(i));
            if (i < to - 1) sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Override
    public String edit(Path path, String oldString, String newString) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (!content.contains(oldString)) {
            throw new IOException("未找到要替换的字符串: " + oldString);
        }
        String updated = content.replace(oldString, newString);
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return updated;
    }

    @Override
    public List<String> glob(String pattern, Path baseDir) throws Exception {
        PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + pattern);
        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(baseDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(baseDir.relativize(p)))
                    .forEach(p -> results.add(p.toString()));
        }
        return results;
    }

    @Override
    public List<String> grep(String pattern, Path baseDir, String include) throws Exception {
        Pattern regex = Pattern.compile(pattern);
        PathMatcher includeMatcher = include != null
                ? baseDir.getFileSystem().getPathMatcher("glob:" + include) : null;
        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(baseDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> includeMatcher == null || includeMatcher.matches(p.getFileName()))
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (int i = 0; i < lines.size(); i++) {
                                Matcher m = regex.matcher(lines.get(i));
                                if (m.find()) {
                                    results.add(p + ":" + (i + 1) + ": " + lines.get(i).trim());
                                }
                            }
                        } catch (IOException ignored) {
                            // 跳过不可读文件
                        }
                    });
        }
        return results;
    }
}
