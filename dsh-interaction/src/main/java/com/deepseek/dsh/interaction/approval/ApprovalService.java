package com.deepseek.dsh.interaction.approval;

import java.util.concurrent.CompletableFuture;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;

/**
 * 审批服务能力缝 —— 对应原 Harness 的 {@code ctx.approval}。
 *
 * <p>提供一次性的人类确认提示。实现可对接 Web UI、CLI 等不同前端。
 * 默认实现 {@link AutoApprovalService} 自动批准全部请求（用于无头/headless 场景）。
 *
 * <p>设计模式：代理（Proxy）—— 工具执行经审批代理把关。
 */
public interface ApprovalService extends Service {

    /**
     * 请求一次人类审批，返回异步结果。
     */
    CompletableFuture<ApprovalResult> request(ApprovalRequest request);

    /** 默认自动批准实现（headless）。 */
    final class AutoApprovalService implements ApprovalService, Plugin {
        @Override
        public CompletableFuture<ApprovalResult> request(ApprovalRequest request) {
            return CompletableFuture.completedFuture(ApprovalResult.granted());
        }

        @Override
        public Disposable apply(Context ctx) {
            return ctx.register(ApprovalService.class, this);
        }
    }
}
