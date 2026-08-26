package com.deepseek.dsh.skill;

import java.util.Optional;

/**
 * 完整技能定义 —— {@link SkillService#get(String, String)} 返回。
 *
 * @param name        kebab-case 标识符
 * @param description 简短路由描述
 * @param whenToUse   可选额外路由指引
 * @param invocation  解析后的模型/用户调用策略
 * @param source      发现来源
 * @param provider    提供者名
 * @param resourceBase 可选相对资源基址
 * @param path        来自磁盘时的绝对文件路径
 * @param content     去除 frontmatter 后的 Markdown 指令体
 */
public record SkillDefinition(
        String name,
        String description,
        Optional<String> whenToUse,
        SkillInvocationPolicy invocation,
        String source,
        String provider,
        Optional<String> resourceBase,
        Optional<String> path,
        String content) {
}
