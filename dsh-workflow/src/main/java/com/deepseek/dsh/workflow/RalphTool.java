package com.deepseek.dsh.workflow;

import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * ralph 工具 —— 对应原 Harness 的 {@code tool-ralph}。
 *
 * <p>启动一个前台 fresh-agent 工作流循环，朝不可变目标推进。
 * Ralph 循环是面向模型的工作流策略（非同会话 goal），由独立轮次组成，
 * 每轮交付结构化交接报告，共享工作区为权威。
 *
 * <p>设计模式：命令（Command）+ 模板方法。
 */
public final class RalphTool extends AbstractTool {

    private final WorkflowService workflow;

    public RalphTool(WorkflowService workflow) {
        this.workflow = workflow;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("ralph", "启动 Ralph 循环——前台 fresh-agent 工作流。")
                .string("objective", "不可变目标描述", true)
                .intProp("max_rounds", "最大轮次（默认 5）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String objective = args.requiredString("objective");
        int maxRounds = args.optionalInt("max_rounds", 5);
        // 提交为工作流任务
        String taskId = "ralph-" + System.nanoTime();
        workflow.submit(WorkflowTask.of(taskId,
                "Ralph 循环: " + objective, "max_rounds=" + maxRounds));
        return "已启动 Ralph 循环（目标: " + objective + ", 最大 " + maxRounds + " 轮）\n"
                + "任务 ID: " + taskId + "\n"
                + "各轮的 fresh agent 将朝目标推进，交接报告将累积在工作区。";
    }
}
