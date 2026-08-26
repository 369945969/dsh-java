package com.deepseek.dsh.skill;

import java.util.Optional;

/**
 * 运行时技能注册输入 —— {@link SkillService#register} 接受。
 *
 * <p>省略的调用策略与提供者字段取默认值（双向可调用、runtime 提供者）。
 *
 * @param name        kebab-case 技能名
 * @param description 简短路由描述（非空）
 * @param whenToUse   可选额外路由指引
 * @param invocation  可选调用策略；省略取默认
 * @param source      发现来源
 * @param resourceBase 可选相对资源基址
 * @param path        可选绝对路径
 * @param content     Markdown 指令体
 */
public record SkillRegistration(
        String name,
        String description,
        Optional<String> whenToUse,
        Optional<SkillInvocationPolicy> invocation,
        String source,
        Optional<String> resourceBase,
        Optional<String> path,
        String content) {

    /** 便捷工厂：仅必需字段。 */
    public static SkillRegistration of(String name, String description, String content) {
        return new SkillRegistration(name, description, Optional.empty(),
                Optional.empty(), "runtime", Optional.empty(), Optional.empty(), content);
    }
}
