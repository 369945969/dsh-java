package com.deepseek.dsh.storage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 本地存储提供者 —— 对应原 Harness 的 {@code storage-json} / 内存后端。
 *
 * <p>默认注册一个名为 {@code default} 的内存后端；当传入数据目录时，
 * 额外注册一个 {@code file} 后端，将 KV 持久化到 {@code storage.json}
 * （原子写入）。后端可插拔，第三方可再 {@link #registerBackend} 追加。
 *
 * <p>设计模式：策略的具体实现 + 注册表（多后端聚合）。
 */
public final class LocalStorageProvider
        extends AbstractCapabilityPlugin<StorageService>
        implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageProvider.class);
    private static final String DEFAULT_BACKEND = "default";
    private static final String FILE_BACKEND = "file";

    private final ConcurrentMap<String, StorageBackend> backends = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalStorageProvider() {
        backends.put(DEFAULT_BACKEND, new MemoryBackend());
    }

    /** 传入数据目录即启用文件后端（原子持久化）。 */
    public LocalStorageProvider(Path dataDir) {
        this();
        backends.put(FILE_BACKEND, new FileBackend(dataDir.resolve("storage.json"), mapper));
    }

    @Override
    protected Class<StorageService> serviceType() {
        return StorageService.class;
    }

    @Override
    public Optional<StorageBackend> getBackend(String name) {
        return Optional.ofNullable(backends.get(name));
    }

    @Override
    public void registerBackend(String name, StorageBackend backend) {
        if (backends.putIfAbsent(name, backend) != null) {
            throw new IllegalStateException("存储后端已存在: " + name);
        }
    }

    /** 默认内存后端 —— 进程内并发 KV，对应 TS 的 memory-backend。 */
    static final class MemoryBackend implements StorageBackend {
        private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void remove(String key) {
            store.remove(key);
        }

        @Override
        public Map<String, String> all() {
            return Map.copyOf(store);
        }
    }

    /** 文件后端 —— JSON 原子持久化，对应 TS 的 storage-json。 */
    static final class FileBackend implements StorageBackend {
        private final Path file;
        private final ObjectMapper mapper;
        private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

        FileBackend(Path file, ObjectMapper mapper) {
            this.file = file;
            this.mapper = mapper;
            load();
        }

        @SuppressWarnings("unchecked")
        private void load() {
            if (!java.nio.file.Files.isReadable(file)) return;
            try {
                Map<String, String> raw = mapper.readValue(file.toFile(), Map.class);
                store.putAll(raw);
            } catch (Exception e) {
                log.warn("加载存储文件失败: {}", e.toString());
            }
        }

        private void save() {
            try {
                ObjectNode root = mapper.createObjectNode();
                store.forEach(root::put);
                java.nio.file.Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
                java.nio.file.Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                log.warn("保存存储文件失败: {}", e.toString());
            }
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(String key, String value) {
            store.put(key, value);
            save();
        }

        @Override
        public void remove(String key) {
            store.remove(key);
            save();
        }

        @Override
        public Map<String, String> all() {
            return Map.copyOf(store);
        }
    }
}
