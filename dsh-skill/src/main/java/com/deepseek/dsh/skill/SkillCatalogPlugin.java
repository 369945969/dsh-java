package com.deepseek.dsh.skill;

import java.util.List;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * 技能目录注入插件 —— 对应原 Harness {@code tool-skill} 的 agent/pre-step 目录注入。
 *
 * <p>在系统提示组装时把已发现的 model-invocable 技能摘要（名+描述）作为
 * {@code <available_skills>} 段落注入，让模型据此调用 {@link SkillTool}（{@code skill}
 * 工具）按名加载技能体。与原 Harness 的 durable 用户角色 {@code <system-reminder>}
 * 目录等价（本 Java 版走系统提示段落，简化实现）。
 *
 * <p>只列 model-invocable 技能（{@code disable-model-invocation: true} 的不进目录），
 * 与原 Harness 一致；技能体不含于此，模型须调用 {@code skill} 工具加载后才据以行动。
 *
 * <p>设计模式：观察者（监听系统提示注入事件）。
 */
public final class SkillCatalogPlugin implements Plugin {

    private final SkillService skills;

    public SkillCatalogPlugin(SkillService skills) {
        this.skills = skills;
    }

    @Override
    public Disposable apply(Context ctx) {
        return ctx.events().on(SystemPromptInjectEvent.class, (event, next) -> {
            try {
                List<SkillSummary> catalog = skills.list(null).stream()
                        .filter(s -> s.invocation() != null && s.invocation().modelInvocable())
                        .toList();
                if (!catalog.isEmpty()) {
                    event.appendSection("skill-catalog", renderCatalog(catalog));
                }
            } catch (RuntimeException e) {
                // 技能发现失败不应阻断系统提示组装
            }
            return next.invoke(event);
        });
    }

    private static String renderCatalog(List<SkillSummary> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("<available_skills>\n");
        for (SkillSummary s : skills) {
            sb.append("- `").append(s.name()).append("`");
            String desc = s.description();
            if (desc != null && !desc.isBlank()) {
                sb.append(": ").append(desc);
            }
            sb.append("\n");
        }
        sb.append("</available_skills>\n");
        sb.append("If the user names a skill, or the task clearly matches a skill's description, ");
        sb.append("call the `skill` tool with the exact skill name to load its full instructions before acting on it.");
        return sb.toString();
    }
}
