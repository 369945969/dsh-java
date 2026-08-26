package com.deepseek.dsh.capability.jobs;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 后台任务运行时 —— 对应原 Harness 的 {@code ctx.jobs}。
 *
 * <p>管理后台异步任务：注册、轮询、取消、完成回调。任务隔离按 owner（会话）。
 * 利用 Java 21 虚拟线程承载大量后台任务。
 *
 * <p>设计模式：注册表 + 命令（后台任务即异步命令）+ 模板方法（插件基类）。
 */
public final class JobService extends AbstractCapabilityPlugin<Jobs> implements Jobs {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(0);

    @Override
    protected Class<Jobs> serviceType() {
        return Jobs.class;
    }

    @Override
    public String submit(String owner, String description, java.util.function.Supplier<String> work) {
        String id = "job-" + idSeq.incrementAndGet();
        Job job = new Job(id, owner, description, JobStatus.RUNNING, null);
        jobs.put(id, job);
        executor.submit(() -> {
            try {
                String output = work.get();
                jobs.put(id, job.withResult(output, JobStatus.DONE));
            } catch (Exception e) {
                jobs.put(id, job.withResult("失败: " + e.getMessage(), JobStatus.FAILED));
            }
        });
        return id;
    }

    @Override
    public Optional<Job> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public String output(String jobId) {
        Job j = jobs.get(jobId);
        return j != null ? j.output() : "（任务不存在）";
    }

    @Override
    public java.util.List<Job> listByOwner(String owner) {
        return jobs.values().stream()
                .filter(j -> j.owner().equals(owner))
                .toList();
    }

    @Override
    public boolean cancel(String jobId) {
        Job j = jobs.get(jobId);
        if (j == null || j.status() != JobStatus.RUNNING) return false;
        jobs.put(jobId, j.withResult("（已取消）", JobStatus.CANCELLED));
        return true;
    }

    /** 关闭线程池。 */
    public void shutdown() {
        executor.close();
    }

    /** 任务状态枚举。 */
    public enum JobStatus { RUNNING, DONE, FAILED, CANCELLED }

    /** 后台任务。 */
    public record Job(
            String id,
            String owner,
            String description,
            JobStatus status,
            String output
    ) {
        Job withResult(String output, JobStatus status) {
            return new Job(id, owner, description, status, output);
        }
    }
}
