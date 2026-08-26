package com.deepseek.dsh.teams;

import java.util.List;
import java.util.Map;

import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * team 工具 —— 对应原 Harness 的团队派发工具。
 *
 * <p>模型可调用：把一个任务并行派发给团队中所有成员 agent，返回综合聚合结果。
 * 适合多视角/多专家并行分析（如多 agent 各自审查一段代码后汇总）。
 *
 * <p>设计模式：命令（Command）—— 把团队派发封装为可调用命令。
 */
public final class TeamTool extends AbstractTool {

    private final TeamsService teams;

    public TeamTool(TeamsService teams) {
        this.teams = teams;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("team", "把任务并行派发给团队所有成员，返回综合结果。")
                .string("task", "要并行派发的任务描述", true)
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String task = args.requiredString("task");
        if (teams.memberNames().isEmpty()) {
            return "（未注册任何团队成员）";
        }
        TeamsService.TeamResult result = teams.runTeamTask(task);
        StringBuilder sb = new StringBuilder(result.summary());
        if (!result.reports().isEmpty()) {
            sb.append("\n\n各成员报告:");
            for (TeamsService.MemberReport r : result.reports()) {
                sb.append("\n[").append(r.name()).append("] ");
                sb.append(r.success() ? r.report() : "失败: " + r.error().orElse("未知"));
            }
        }
        return sb.toString();
    }
}
