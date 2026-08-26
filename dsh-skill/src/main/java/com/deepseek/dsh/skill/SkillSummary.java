package com.deepseek.dsh.skill;

import java.util.Optional;

/**
 * 技能调用中立的元数据 —— {@link SkillService#list()} 返回。
 *
 * @param name        kebab-case 标识符
 * @param description 简短路由描述
 * @param whenToUse   可选额外路由指引
 * @param invocation  解析后的模型/用户调用策略
 * @param source      发现来源（如 project-dsh、user-agents、bundled）
 * @param provider    拥有此技能体的提供者名
 * @param resourceBase 可选提供者特定的相对资源基址（目录路径等）
 */
public record SkillSummary(
        String name,
        String description,
        Optional<String> whenToUse,
        SkillInvocationPolicy invocation,
        String source,
        String provider,
        Optional<String> resourceBase) {
}
