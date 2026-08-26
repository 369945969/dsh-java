package com.deepseek.dsh.settings;

import java.util.Map;
import java.util.Optional;

import com.deepseek.dsh.core.context.Service;

/**
 * 用户设置能力缝 —— 对应原 Harness 的 {@code ctx.settings}。
 *
 * <p>命名空间式的用户配置：键值对，可分组。提供者可插拔（文件、数据库等）。
 *
 * <p>设计模式：策略 + SPI。
 */
public interface SettingsService extends Service {

    /** 获取某命名空间下某键的值。 */
    Optional<String> get(String namespace, String key);

    /** 设置某命名空间下某键的值。 */
    void set(String namespace, String key, String value);

    /** 获取某命名空间的全部键值。 */
    Map<String, String> getAll(String namespace);
}
