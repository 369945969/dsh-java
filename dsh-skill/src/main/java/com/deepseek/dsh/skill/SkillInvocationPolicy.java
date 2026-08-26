package com.deepseek.dsh.skill;

/**
 * 技能调用策略 —— 是否对模型/用户可见。
 *
 * @param modelInvocable 模型面向的目录与加载器是否包含此技能
 * @param userInvocable  人类面向的命令目录与加载器是否包含此技能
 */
public record SkillInvocationPolicy(boolean modelInvocable, boolean userInvocable) {
    /** 默认允许模型与用户双向调用。 */
    public static SkillInvocationPolicy defaultPolicy() {
        return new SkillInvocationPolicy(true, true);
    }
}
