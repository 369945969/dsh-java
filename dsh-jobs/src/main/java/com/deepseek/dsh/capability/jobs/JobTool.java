package com.deepseek.dsh.capability.jobs;

import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 后台任务控制工具 —— 对应原 Harness 的 {@code tool-jobs}。
 *
 * <p>提供 job_output / job_list / job_kill 三个操作，让模型能管理后台任务。
 */
public final class JobTool extends AbstractTool {

    private final Jobs jobs;

    public JobTool(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("job", "管理后台任务（output/list/kill）。")
                .enumStr("action", java.util.List.of("output", "list", "kill"),
                        "操作类型", true)
                .string("jobId", "任务 ID（output/kill 必填）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String action = args.requiredString("action");
        return switch (action) {
            case "output" -> {
                String id = args.requiredString("jobId");
                yield jobs.output(id);
            }
            case "list" -> {
                var list = jobs.listByOwner(ctx.sessionId().value());
                yield list.isEmpty() ? "（无后台任务）"
                        : list.stream()
                        .map(j -> j.id() + " [" + j.status() + "] " + j.description())
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            }
            case "kill" -> {
                String id = args.requiredString("jobId");
                yield jobs.cancel(id) ? "已取消: " + id : "取消失败: " + id;
            }
            default -> "未知操作: " + action;
        };
    }
}
