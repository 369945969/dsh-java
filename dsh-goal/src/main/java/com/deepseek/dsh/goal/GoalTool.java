package com.deepseek.dsh.goal;

import java.util.List;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * goal 工具 —— 对应原 Harness 的 {@code tool-goal}。
 *
 * <p>模型面向的目标管理工具：设置、查询、推进目标。
 * 带执行时权限检查（只有 active 目标可推进轮次）。
 *
 * <p>设计模式：命令（Command）+ 模板方法。
 */
public final class GoalTool extends AbstractTool {

    private final Goals goals;

    public GoalTool(Goals goals) {
        this.goals = goals;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("goal", "管理会话目标（set/status/advance/complete）。")
                .enumStr("action", List.of("set", "status", "advance", "complete"),
                        "操作类型", true)
                .string("objective", "目标描述（set 必填）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String action = args.requiredString("action");
        SessionId sid = ctx.sessionId();
        return switch (action) {
            case "set" -> {
                String obj = args.requiredString("objective");
                Goal g = goals.arm(sid, obj);
                yield "目标已激活: " + g.objective();
            }
            case "status" -> {
                var cur = goals.current(sid);
                yield cur.map(g -> "目标: " + g.objective()
                        + "\n阶段: " + g.phase()
                        + "\n已完成轮次: " + g.roundsCompleted())
                        .orElse("（无活跃目标）");
            }
            case "advance" -> {
                Goal g = goals.advanceRound(sid);
                yield "已推进至第 " + g.roundsCompleted() + " 轮";
            }
            case "complete" -> {
                goals.setPhase(sid, GoalPhase.COMPLETE);
                yield "目标已完成";
            }
            default -> "未知操作: " + action;
        };
    }
}
