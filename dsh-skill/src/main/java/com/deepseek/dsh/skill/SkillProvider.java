package com.deepseek.dsh.skill;

import java.util.List;
import java.util.Optional;

/**
 * 技能提供者 —— 单一技能来源（如本地目录或远程注册表）。
 *
 * <p>对应原 Harness 的 {@code SkillProvider}：提供者同步注册，远程初始化、
 * 认证与发现延迟到 {@link #list} 内完成。
 *
 * <p>设计模式：策略（可互换来源）。
 */
public interface SkillProvider {

    /** 注册表内唯一的提供者名。 */
    String name();

    /**
     * 列出当前查找上下文的候选技能。
     *
     * @param cwd 工作区选择器；可为 null
     * @return 候选数组（含 rank 等字段）
     */
    List<SkillCandidate> list(String cwd);

    /**
     * 为先前列出的候选加载完整技能体。
     *
     * @param candidate 先前由本提供者返回的胜出候选
     * @return 完整技能，或 {@code Optional#empty()}（不再可加载）
     */
    Optional<SkillDefinition> get(SkillCandidate candidate);

    /**
     * 提供者目录条目 —— 注册表据此合并并解析同名胜出者。
     *
     * @param rank 优先级（低者同名胜出）
     */
    record SkillCandidate(
            String name,
            String description,
            Optional<String> whenToUse,
            SkillInvocationPolicy invocation,
            String source,
            String provider,
            Optional<String> resourceBase,
            Optional<String> path,
            int rank,
            Object locator) {
    }
}
