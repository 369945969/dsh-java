package com.deepseek.dsh.storage;

import java.util.Map;
import java.util.Optional;

import com.deepseek.dsh.core.context.Service;

/**
 * 通用存储中心缝 —— 对应原 Harness 的 {@code ctx.storage}。
 *
 * <p>命名的 KV 存储后端注册表 + 域数据形式。
 * 后端可插拔：JSON 文件、SQLite 等。
 *
 * <p>设计模式：策略 + SPI。
 */
public interface StorageService extends Service {

    /** 获取一个命名后端。 */
    Optional<StorageBackend> getBackend(String name);

    /** 注册一个命名后端。 */
    void registerBackend(String name, StorageBackend backend);

    /** 域数据形式：schema 校验 + 事件发射的 KV 域。 */
    interface StorageBackend {
        Optional<String> get(String key);
        void put(String key, String value);
        void remove(String key);
        Map<String, String> all();
    }
}
