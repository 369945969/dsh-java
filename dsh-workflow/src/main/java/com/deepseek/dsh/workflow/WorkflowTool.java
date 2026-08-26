package com.deepseek.dsh.workflow;

import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * workflow 工具 —— 对应原 Harness 的 {@code tool-workflow}。
 *
 * <p>提交异步工作流任务，返回任务 ID 供后续轮询。
 */
public final class WorkflowTool extends AbstractTool {

    private final WorkflowService workflow;

    public WorkflowTool(WorkflowService workflow) {
        this.workflow = workflow;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("workflow", "提交后台工作流任务并返回任务 ID。")
                .string("description", "任务描述", true)
                .string("payload", "任务负载")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String desc = args.requiredString("description");
        String payload = args.optionalString("payload", "");
        String taskId = "wf-" + System.nanoTime();
        var future = workflow.submit(WorkflowTask.of(taskId, desc, payload));
        // 不等待完成，返回任务 ID 供轮询
        return "已提交工作流任务: " + taskId + "（状态: " + workflow.status(taskId) + "）";
    }
}
