package com.deepseek.dsh.credentials;

import java.util.concurrent.CompletableFuture;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 授权服务默认实现 —— 对应原 Harness 的 {@code ctx.authorization} 默认行为。
 *
 * <p>把授权流程函数异步执行；流程返回 null 时给出「（授权失败）」。
 *
 * <p>设计模式：策略的具体实现 + 模板方法。
 */
public final class AuthorizationService
        extends AbstractCapabilityPlugin<Authorization>
        implements Authorization {

    @Override
    protected Class<Authorization> serviceType() {
        return Authorization.class;
    }

    @Override
    public CompletableFuture<String> authorize(String credentialReference,
                                               java.util.function.Function<String, String> flow) {
        return CompletableFuture.supplyAsync(() -> {
            String result = flow.apply(credentialReference);
            return result != null ? result : "（授权失败）";
        });
    }
}
