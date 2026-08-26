package com.deepseek.dsh.capability.jobs;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.deepseek.dsh.core.context.Service;

/**
 * 后台任务服务能力缝 —— 对应原 Harness 的 {@code ctx.jobs}。
 *
 * <p>提供后台任务的生命周期管理。owner 隔离保证不同会话的互不干扰。
 */
public interface Jobs extends Service {

    /** 提交一个后台任务，返回任务 ID。 */
    String submit(String owner, String description, Supplier<String> work);

    /** 查询任务状态。 */
    Optional<JobService.Job> get(String jobId);

    /** 读取任务输出（完成时）。 */
    String output(String jobId);

    /** 列出某 owner 的全部任务。 */
    List<JobService.Job> listByOwner(String owner);

    /** 取消任务。 */
    boolean cancel(String jobId);
}
