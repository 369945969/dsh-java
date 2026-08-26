package com.deepseek.dsh.workflow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * worker-thread 工作流提供者 —— 对应原 Harness 的 {@code workflow-worker-thread}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractCapabilityPlugin}，消除样板；
 * 用虚拟线程池执行异步任务，利用 Java 21 虚拟线程轻量承载大量并发后台任务。
 *
 * <p>设计模式：策略的具体实现 + 命令调度器 + 模板方法。
 */
public final class WorkerThreadWorkflowProvider
        extends AbstractCapabilityPlugin<WorkflowService>
        implements WorkflowService {

    private final ExecutorService executor;
    private final ConcurrentMap<String, String> statuses = new ConcurrentHashMap<>();

    public WorkerThreadWorkflowProvider() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    protected Class<WorkflowService> serviceType() {
        return WorkflowService.class;
    }

    @Override
    public CompletableFuture<WorkflowResult> submit(WorkflowTask task) {
        statuses.put(task.id(), "RUNNING");
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 任务执行由调用方通过 payload 定义；此处为占位执行
                String output = "任务 '" + task.description() + "' 完成";
                statuses.put(task.id(), "DONE");
                return WorkflowResult.ok(task.id(), output);
            } catch (Exception e) {
                statuses.put(task.id(), "FAILED");
                return WorkflowResult.failed(task.id(), e.getMessage());
            }
        }, executor);
    }

    @Override
    public String status(String taskId) {
        return statuses.getOrDefault(taskId, "UNKNOWN");
    }

    /** 关闭线程池。 */
    public void shutdown() {
        executor.close();
    }
}
