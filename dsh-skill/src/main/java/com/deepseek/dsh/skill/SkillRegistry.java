package com.deepseek.dsh.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 技能注册表 —— 对应原 Harness 的 {@code SkillRegistry}。
 *
 * <p>分层的提供者注册表：宿主与每作用域插件落地到对应层。一次读取合并全局层
 * 与查看作用域链 —— 最近层的同名条目直接胜出，rank 仅在同一层内裁决同名胜出。
 * 本 Java 版简化为单层 + 运行时技能：合并各提供者候选与运行时注册，
 * 同名按 rank 升序、提供者注册序、局部序裁决，输出按名排序的摘要。
 *
 * <p>设计模式：注册表 + 责任裁决（多源合并）。
 */
public final class SkillRegistry
        extends AbstractCapabilityPlugin<SkillService>
        implements SkillService {

    private final List<SkillProvider> providers = new ArrayList<>();
    private final ConcurrentMap<String, SkillDefinition> runtime = new ConcurrentHashMap<>();
    private volatile int providerSeq = 0;
    private final List<int[]> providerOrders = new ArrayList<>(); // 与 providers 对齐的注册序

    @Override
    protected Class<SkillService> serviceType() {
        return SkillService.class;
    }

    @Override
    public synchronized void registerProvider(SkillProvider provider) {
        providers.add(provider);
        providerOrders.add(new int[]{providerSeq++});
    }

    @Override
    public void register(SkillRegistration skill) {
        if (!SkillService.isSkillName(skill.name())) {
            throw new IllegalArgumentException("非法技能名: " + skill.name());
        }
        if (skill.description() == null || skill.description().isEmpty()) {
            throw new IllegalArgumentException("技能 " + skill.name() + " 需要描述");
        }
        SkillInvocationPolicy inv = skill.invocation().orElse(SkillInvocationPolicy.defaultPolicy());
        SkillDefinition def = new SkillDefinition(
                skill.name(), skill.description(), skill.whenToUse(),
                inv, skill.source(), "runtime", skill.resourceBase(),
                skill.path(), skill.content());
        runtime.putIfAbsent(def.name(), def);
    }

    @Override
    public List<SkillSummary> list(String cwd) {
        return collect(cwd).stream()
                .map(SkillRegistry::toSummary)
                .sorted(Comparator.comparing(SkillSummary::name))
                .toList();
    }

    @Override
    public Optional<SkillDefinition> get(String name, String cwd) {
        if (!SkillService.isSkillName(name)) return Optional.empty();
        for (ProviderEntry e : collect(cwd)) {
            if (e.candidate().name().equals(name)) {
                try {
                    Optional<SkillDefinition> def = e.provider().get(e.candidate());
                    if (def.isPresent() && def.get().name().equals(name)) return def;
                } catch (Exception ex) {
                    // 提供者加载失败：当作不可加载，跳过
                }
            }
        }
        return Optional.empty();
    }

    /** 合并运行时技能与各提供者候选，按 rank/注册序裁决同名胜出。 */
    private List<ProviderEntry> collect(String cwd) {
        List<ProviderEntry> entries = new ArrayList<>();
        // 运行时技能
        int runtimeOrder = 0;
        for (SkillDefinition def : runtime.values()) {
            entries.add(new ProviderEntry(
                    new SkillProvider.SkillCandidate(
                            def.name(), def.description(), def.whenToUse(),
                            def.invocation(), def.source(), "runtime",
                            def.resourceBase(), def.path(), 250, def),
                    RUNTIME_PROVIDER, -1, runtimeOrder++));
        }
        // 提供者候选
        int idx = 0;
        for (SkillProvider p : providers) {
            int order = providerOrders.get(idx)[0];
            int local = 0;
            try {
                for (SkillProvider.SkillCandidate c : p.list(cwd)) {
                    entries.add(new ProviderEntry(c, p, order, local++));
                }
            } catch (Exception e) {
                // 提供者发现失败：跳过，不阻断其他提供者
            }
            idx++;
        }
        // 排序：rank 升序 → 提供者注册序 → 局部序
        entries.sort(Comparator
                .<ProviderEntry, Integer>comparing(e -> e.candidate().rank())
                .thenComparingInt(ProviderEntry::providerOrder)
                .thenComparingInt(ProviderEntry::localOrder));
        // 同名去重：保留首个胜出者
        List<ProviderEntry> winners = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ProviderEntry e : entries) {
            if (seen.add(e.candidate().name())) winners.add(e);
        }
        return winners;
    }

    private static SkillSummary toSummary(ProviderEntry e) {
        var c = e.candidate();
        return new SkillSummary(c.name(), c.description(), c.whenToUse(),
                c.invocation(), c.source(), c.provider(), c.resourceBase());
    }

    /** 运行时技能的内置提供者（仅 owns get()）。 */
    private static final SkillProvider RUNTIME_PROVIDER = new SkillProvider() {
        @Override public String name() { return "runtime"; }
        @Override public List<SkillProvider.SkillCandidate> list(String cwd) { return List.of(); }
        @Override public Optional<SkillDefinition> get(SkillProvider.SkillCandidate candidate) {
            return Optional.ofNullable((SkillDefinition) candidate.locator());
        }
    };

    private record ProviderEntry(SkillProvider.SkillCandidate candidate,
                                 SkillProvider provider,
                                 int providerOrder,
                                 int localOrder) {}
}
