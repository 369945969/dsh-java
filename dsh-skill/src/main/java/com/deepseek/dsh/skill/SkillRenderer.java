package com.deepseek.dsh.skill;

/**
 * 技能内容渲染器 —— 对应原 Harness 的 {@code renderSkillContent}。
 *
 * <p>把一个已加载技能渲染为模型可见的标准 {@code <skill_content>} 块，
 * 工具结果与用户显式调用注入共用此形态，使模型在两条路径上看到同一规范包装。
 * 名称经属性转义；体原样嵌入（技能为可信本地内容，调用方文本在此包装外）。
 *
 * <p>设计模式：策略（渲染策略）—— 纯函数，无状态。
 */
public final class SkillRenderer {

    private SkillRenderer() {}

    /** 渲染一个已加载技能为 {@code <skill_content>} 块。 */
    public static String render(SkillDefinition skill) {
        return "<skill_content name=\"" + escapeAttr(skill.name()) + "\">\n"
                + "<skill_resources>\n"
                + resourceHint(skill) + "\n"
                + "</skill_resources>\n\n"
                + "<skill_instructions>\n"
                + skill.content() + "\n"
                + "</skill_instructions>\n"
                + "</skill_content>";
    }

    /** 资源指引：有基址给基址，否则归提供者管理。 */
    private static String resourceHint(SkillDefinition skill) {
        return skill.resourceBase()
                .map(base -> "此技能的基址: " + escapeText(base) + "\n"
                        + "按需加载引用资源，相对路径基于此基址解析。")
                .orElseGet(() -> "此技能的资源由提供者 \"" + escapeText(skill.provider()) + "\" 管理。\n"
                        + "按需加载引用资源。");
    }

    /** 属性转义：&, ", <。 */
    private static String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /** 文本转义：&, <, >。 */
    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
