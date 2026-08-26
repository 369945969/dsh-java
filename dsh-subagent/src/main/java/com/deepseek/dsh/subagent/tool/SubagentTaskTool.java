package com.deepseek.dsh.subagent.tool;

import java.util.List;
import java.util.Map;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentService;
import com.deepseek.dsh.tools.registry.Tool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * task 委派工具 —— 对应原 Harness 的 {@code tool-subagent}。
 *
 * <p>让主 agent 将一个子任务委派给子 agent 执行。子 agent 可有不同人格或受限工具集，
 * 完成后返回摘要报告。
 *
 * <p>设计模式：命令（Command）+ 代理（委派给子 agent）。
 */
public final class SubagentTaskTool implements Tool {

    private final Agent subagent;

    public SubagentTaskTool(Agent subagent) {
        this.subagent = subagent;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("task", "将子任务委派给一个子 agent 执行并返回摘要报告。", Map.of(
                "type", "object",
                "properties", Map.of(
                        "description", Map.of("type", "string",
                                "description", "子任务的详细描述（目标、约束）"),
                        "prompt", Map.of("type", "string",
                                "description", "传给子 agent 的具体提示（可选）")
                ),
                "required", List.of("description")
        ));
    }

    @Override
    public String invoke(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        Context context = ctx.context();
        SubagentService subagents = context.get(SubagentService.class).orElse(null);
        if (subagents == null) {
            return "（subagent 服务未注册）";
        }
        String task = (String) arguments.get("description");
        String prompt = (String) arguments.get("prompt");
        String fullTask = prompt != null ? prompt : task;

        DelegationResult result = subagents.delegate(
                ctx.sessionId(), ctx.scopeKey(), context, subagent, fullTask);
        return result.success()
                ? "子任务完成:\n" + result.report()
                : "子任务失败:\n" + result.report();
    }
}
