package com.deepseek.dsh.credentials;

import java.util.concurrent.CompletableFuture;

import com.deepseek.dsh.core.context.Service;

/**
 * 授权能力缝 —— 对应原 Harness 的 {@code ctx.authorization}。
 *
 * <p>插件拥有的授权流程：通过对话获取一个凭据（如 OAuth 授权码流）。
 * 与 {@link CredentialsService} 互补：凭据存储已有值，授权流程获取新值。
 *
 * <p>设计模式：策略 + SPI。
 */
public interface Authorization extends Service {

    /**
     * 执行授权流程获取凭据。
     *
     * @param credentialReference 凭据引用名
     * @param flow                授权流程函数（输入引用名，返回凭据值）
     * @return 授权得到的凭据值（future）；失败时返回「（授权失败）」
     */
    CompletableFuture<String> authorize(String credentialReference,
                                         java.util.function.Function<String, String> flow);
}
