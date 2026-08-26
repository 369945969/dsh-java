package com.deepseek.dsh.skill;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.deepseek.dsh.core.context.Service;

/**
 * 技能能力缝 —— 对应原 Harness 的 {@code ctx.skills}。
 *
 * <p>技能提供者注册表的 Java 等价物：合并多个提供者的目录，解析同名技能的
 * 胜出者，对外暴露胜出的摘要与完整定义。具体来源（本地文件系统、远程注册表）
 * 由 {@link SkillProvider} 实现；本服务只做合并、解析与暴露。
 *
 * <p>设计模式：注册表 + 策略（多提供者聚合）。
 */
public interface SkillService extends Service {

    /** 技能名 kebab-case 校验：{@code ^[a-z0-9]+(?:-[a-z0-9]+)*$}。 */
    Pattern NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /** 判定一个字符串是否合法 kebab-case 技能名。 */
    static boolean isSkillName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * 列出调用中立的技能摘要（按名排序）。
     *
     * @param cwd 工作区选择器（选择项目根）；可为 null
     * @return 所有胜出摘要
     */
    List<SkillSummary> list(String cwd);

    /**
     * 加载并校验胜出的技能体。
     *
     * @param name kebab-case 技能名
     * @param cwd  工作区选择器；可为 null
     * @return 完整技能（含体），或 {@code Optional#empty()}
     */
    Optional<SkillDefinition> get(String name, String cwd);

    /**
     * 运行时注册一个只读技能。
     *
     * @param skill 技能定义输入；省略的调用策略与提供者字段取默认值
     */
    void register(SkillRegistration skill);

    /** 注册一个技能提供者。 */
    void registerProvider(SkillProvider provider);
}
