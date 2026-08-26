package com.deepseek.dsh.settings;

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
 * 文件设置提供者 —— 对应原 Harness 的 {@code settings-file}。
 *
 * <p>从 YAML 风格的文件加载/保存用户设置。简化实现用 JSON 嵌套对象
 * （{@code {namespace: {key: value}}}）。
 *
 * <p>设计模式：策略的具体实现 + 模板方法。
 */
public final class FileSettingsProvider
        extends AbstractCapabilityPlugin<SettingsService>
        implements SettingsService {

    private static final Logger log = LoggerFactory.getLogger(FileSettingsProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path settingsFile;
    private final ConcurrentMap<String, Map<String, String>> data = new ConcurrentHashMap<>();

    public FileSettingsProvider(Path settingsFile) {
        this.settingsFile = settingsFile;
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!java.nio.file.Files.isReadable(settingsFile)) return;
        try {
            Map<String, Object> raw = mapper.readValue(settingsFile.toFile(), Map.class);
            raw.forEach((ns, vals) -> {
                if (vals instanceof Map m) {
                    data.put(ns, new ConcurrentHashMap<>(m));
                }
            });
        } catch (Exception e) {
            log.warn("加载设置文件失败: {}", e.toString());
        }
    }

    @Override
    protected Class<SettingsService> serviceType() {
        return SettingsService.class;
    }

    @Override
    public Optional<String> get(String namespace, String key) {
        return Optional.ofNullable(data.getOrDefault(namespace, Map.of()).get(key));
    }

    @Override
    public void set(String namespace, String key, String value) {
        data.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(key, value);
        save();
    }

    @Override
    public Map<String, String> getAll(String namespace) {
        return Map.copyOf(data.getOrDefault(namespace, Map.of()));
    }

    private void save() {
        try {
            ObjectNode root = mapper.createObjectNode();
            data.forEach((ns, vals) -> {
                ObjectNode node = root.putObject(ns);
                vals.forEach(node::put);
            });
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), root);
        } catch (Exception e) {
            log.warn("保存设置文件失败: {}", e.toString());
        }
    }
}
