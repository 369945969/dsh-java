package com.deepseek.dsh.session.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;

/**
 * JSONL 会话持久化 —— 将 {@link SessionEvent} 以行分隔 JSON 形式追加到文件。
 *
 * <p>对应原 Harness 的 {@code session-persistence-jsonl}。追加写入保证崩溃安全；
 * 重启时按行重放重建日志。
 *
 * <p>设计模式：仓储（Repository）+ 事件存储后端。
 */
public final class JsonlSessionStore implements SessionStore {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path baseDir;

    public JsonlSessionStore(Path baseDir) throws IOException {
        this.baseDir = baseDir;
        Files.createDirectories(baseDir);
    }

    @Override
    public void append(SessionEvent event) throws IOException {
        Path file = pathFor(event.sessionId());
        ObjectNode node = mapper.valueToTree(event);
        String line = mapper.writeValueAsString(node);
        Files.writeString(file, line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public List<SessionEvent> load(SessionId sessionId) throws IOException {
        Path file = pathFor(sessionId);
        if (!Files.exists(file)) return List.of();
        List<SessionEvent> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            out.add(mapper.readValue(line, SessionEvent.class));
        }
        return out;
    }

    @Override
    public List<SessionId> listAll() throws IOException {
        List<SessionId> ids = new ArrayList<>();
        try (var stream = Files.list(baseDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (name.endsWith(".jsonl")) {
                    ids.add(SessionId.of(name.substring(0, name.length() - ".jsonl".length())));
                }
            }
        }
        return ids;
    }

    private Path pathFor(SessionId id) {
        return baseDir.resolve(id.value().replaceAll("[^A-Za-z0-9_-]", "_") + ".jsonl");
    }
}
