package com.deepseek.dsh.skill;

import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * skill 工具 —— 对应原 Harness 的 {@code tool-skill}。
 *
 * <p>模型可调用：按名加载一个 model-invocable 技能并返回其标准
 * {@code <skill_content>} 渲染。技能体作为指令注入，让模型据此执行。
 *
 * <p>设计模式：命令（Command）—— 把技能加载封装为可调用命令。
 */
public final class SkillTool extends AbstractTool {

    private final SkillService skills;

    public SkillTool(SkillService skills) {
        this.skills = skills;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("skill", "按名加载并渲染一个技能（注入技能指令）。")
                .string("name", "kebab-case 技能名", true)
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String name = args.requiredString("name");
        return skills.get(name, null)
                .map(def -> {
                    if (!def.invocation().modelInvocable()) {
                        return "技能 \"" + name + "\" 不允许模型调用。";
                    }
                    return SkillRenderer.render(def);
                })
                .orElseGet(() -> "未找到技能: " + name);
    }
}
