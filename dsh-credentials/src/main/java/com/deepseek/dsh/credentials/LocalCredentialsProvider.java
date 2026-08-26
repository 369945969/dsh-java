package com.deepseek.dsh.credentials;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 本地文件凭据提供者 —— 对应原 Harness 的 {@code credentials-local}。
 *
 * <p>从 {@code $DSH_HOME/.env} 加载凭据，叠加当前进程环境变量。
 * 凭据引用名即环境变量名（如 {@code DEEPSEEK_API_KEY}）。
 *
 * <p>设计模式：策略的具体实现 + 模板方法。
 */
public final class LocalCredentialsProvider
        extends AbstractCapabilityPlugin<CredentialsService>
        implements CredentialsService {

    private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

    public LocalCredentialsProvider() {
    }

    /** 从指定 {@code .env} 文件加载凭据并叠加进程环境变量。 */
    public LocalCredentialsProvider(java.nio.file.Path envFile) {
        loadFromEnvFile(envFile);
    }

    @Override
    protected Class<CredentialsService> serviceType() {
        return CredentialsService.class;
    }

    /** 从 .env 文件加载凭据（叠加当前进程环境变量）。 */
    public void loadFromEnvFile(java.nio.file.Path envFile) {
        // 进程环境变量始终叠加（即便 .env 不存在/不可读）
        System.getenv().forEach(store::put);
        if (!java.nio.file.Files.isReadable(envFile)) return;
        try (var lines = java.nio.file.Files.lines(envFile)) {
            lines.filter(l -> !l.isBlank() && !l.startsWith("#") && l.contains("="))
                    .forEach(line -> {
                        int eq = line.indexOf('=');
                        String key = line.substring(0, eq).trim();
                        String val = line.substring(eq + 1).trim()
                                .replaceAll("^\"|\"$", "");
                        store.put(key, val);
                    });
        } catch (Exception e) {
            // .env 不存在或不可读是正常的，不阻断
        }
    }

    @Override
    public Optional<String> get(String referenceName) {
        return Optional.ofNullable(store.get(referenceName));
    }

    @Override
    public java.util.List<String> references() {
        return java.util.List.copyOf(store.keySet());
    }

    @Override
    public void register(String referenceName, String value) {
        store.put(referenceName, value);
    }
}
