package com.deepseek.dsh.workflow;

import java.util.concurrent.CompletableFuture;

/**
 * 工作流能力缝 —— 对应原 Harness 的 {@code ctx.workflow}。
 *
 * <p>提供异步任务提交与结果轮询，使 agent 能将长任务移至后台并在结果就绪后被唤醒。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code WorkerThreadWorkflowProvider}。</li>
 *   <li><b>消费者</b>：{@code workflow} / {@code ralph} 工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + 命令（异步任务即命令对象）。
 */
public interface WorkflowService extends com.deepseek.dsh.core.context.Service {

    /** 提交一个异步任务，返回 future。 */
    CompletableFuture<WorkflowResult> submit(WorkflowTask task);

    /** 查询任务状态（若已知）。 */
    String status(String taskId);
}
