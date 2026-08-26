package com.deepseek.dsh.credentials;

import java.util.Map;
import java.util.Optional;

import com.deepseek.dsh.core.context.Service;

/**
 * 凭据能力缝 —— 对应原 Harness 的 {@code ctx.credentials}。
 *
 * <p>设置引用凭据（引用而非明文值），提供者拥有实际值。
 * 例如 API Key、OAuth Token 等。
 *
 * <p>设计模式：策略 + SPI。
 */
public interface CredentialsService extends Service {

    /** 按引用名获取凭据值。 */
    Optional<String> get(String referenceName);

    /** 列出全部已注册的凭据引用名。 */
    java.util.List<String> references();

    /** 注册一个凭据引用（运行时可逆）。 */
    void register(String referenceName, String value);
}
