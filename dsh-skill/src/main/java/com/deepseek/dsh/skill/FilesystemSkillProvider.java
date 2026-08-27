package com.deepseek.dsh.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件系统技能提供者 —— 对应原 Harness 的 {@code skill-filesystem}。
 *
 * <p>从项目、自定义与用户根发现目录捆绑与扁平 Markdown 技能，解析 YAML
 * frontmatter（name/description/whenToUse/调用策略），通过 {@link SkillService}
 * 暴露。本 Java 版用最小 frontmatter 解析器（无 YAML 依赖），支持
 * {@code key: value} 与布尔字面量。
 *
 * <p><b>发现规则</b>：根下扁平 {@code *.md} 或目录内 {@code SKILL.md}；
 * 项目根的 {@code .dsh/skills}（rank 100）→ 自定义目录（300）→
 * 用户 {@code ~/.dsh/skills}（400）。
 *
 * <p>设计模式：策略的具体实现 + 模板方法（发现/解析骨架抽出）。
 */
public final class FilesystemSkillProvider implements SkillProvider {

    private static final Logger log = LoggerFactory.getLogger(FilesystemSkillProvider.class);

    private record SkillRoot(String path, String source, int rank) {}

    private final List<SkillRoot> roots;

    /** 仅自定义根。 */
    public FilesystemSkillProvider(List<String> customRoots) {
        this.roots = customRoots.stream()
                .map(r -> new SkillRoot(r, "custom", 300))
                .toList();
    }

    /** 项目根 + 自定义根 + 用户根（完整默认发现）。 */
    public FilesystemSkillProvider(String projectRoot, List<String> customRoots, String dshHome) {
        List<SkillRoot> list = new ArrayList<>();
        if (projectRoot != null) {
            list.add(new SkillRoot(Path.of(projectRoot, ".dsh", "skills").toString(), "project-dsh", 100));
        }
        customRoots.forEach(r -> list.add(new SkillRoot(r, "custom", 300)));
        if (dshHome != null) {
            list.add(new SkillRoot(Path.of(dshHome, "skills").toString(), "user-dsh", 400));
        }
        this.roots = list;
    }

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    public List<SkillCandidate> list(String cwd) {
        List<SkillCandidate> candidates = new ArrayList<>();
        for (SkillRoot root : roots) {
            for (ParsedSkill parsed : discoverRoot(root)) {
                candidates.add(new SkillCandidate(
                        parsed.name, parsed.description, parsed.whenToUse,
                        parsed.invocation, root.source, name(),
                        Optional.of(parsed.directory), Optional.of(parsed.path),
                        root.rank, parsed.path));
            }
        }
        return candidates;
    }

    @Override
    public Optional<SkillDefinition> get(SkillCandidate candidate) {
        Object locator = candidate.locator();
        if (!(locator instanceof String path)) return Optional.empty();
        ParsedSkill parsed = parseSkillFile(path);
        if (parsed == null) return Optional.empty();
        return Optional.of(new SkillDefinition(
                parsed.name, parsed.description, parsed.whenToUse,
                parsed.invocation, candidate.source(), candidate.provider(),
                Optional.of(parsed.directory), Optional.of(parsed.path),
                parsed.content));
    }

    /** 发现一个根下的全部技能候选。 */
    private List<ParsedSkill> discoverRoot(SkillRoot root) {
        List<ParsedSkill> skills = new ArrayList<>();
        List<Path> entries;
        try (var stream = Files.list(Path.of(root.path()))) {
            entries = stream.sorted().toList();
        } catch (Exception e) {
            // 根不存在或不可读是正常的，返回空
            return skills;
        }
        for (Path entry : entries) {
            try {
                Path skillFile;
                String directory;
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    skillFile = entry.resolve("SKILL.md");
                    directory = entry.toString();
                } else if (name.endsWith(".md")) {
                    skillFile = entry;
                    directory = root.path();
                } else {
                    continue;
                }
                ParsedSkill parsed = parseSkillFile(skillFile.toString());
                if (parsed != null) skills.add(parsed);
            } catch (Exception e) {
                log.warn("Failed to parse skill entry {}: {}", entry, e.toString());
            }
        }
        return skills;
    }

    /** 解析一个技能文件：frontmatter + 体。 */
    private ParsedSkill parseSkillFile(String path) {
        String raw;
        try {
            raw = Files.readString(Path.of(path));
        } catch (IOException e) {
            return null;
        }
        FrontMatter fm = parseFrontmatter(raw);
        if (fm == null) return null;
        String name = strField(fm.data, "name");
        String description = strField(fm.data, "description");
        if (name == null || description == null) return null;
        if (!SkillService.isSkillName(name)) return null;
        Optional<String> whenToUse = Optional.ofNullable(strField(fm.data, "whenToUse"));
        SkillInvocationPolicy invocation = parseInvocation(fm.data);
        String directory = Path.of(path).getParent() != null
                ? Path.of(path).getParent().toString() : Path.of(path).toString();
        return new ParsedSkill(name, description, whenToUse, invocation,
                directory, path, fm.body.trim());
    }

    /** 最小 frontmatter 解析：{@code ---\n key: value \n ---\n body}。 */
    private static FrontMatter parseFrontmatter(String raw) {
        int firstNewline = raw.indexOf('\n');
        if (firstNewline < 0) return null;
        String firstLine = raw.substring(0, firstNewline).trim();
        if (!firstLine.equals("---")) return null;
        int closeStart = raw.indexOf("\n---", firstNewline);
        if (closeStart < 0) return null;
        String yaml = raw.substring(firstNewline + 1, closeStart);
        int bodyStart = raw.indexOf('\n', closeStart + 1);
        String body = bodyStart < 0 ? "" : raw.substring(bodyStart + 1);
        Map<String, String> data = new HashMap<>();
        for (String line : yaml.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            if (!key.isEmpty()) data.put(key, val);
        }
        return new FrontMatter(data, body);
    }

    private static String strField(Map<String, String> data, String key) {
        String v = data.get(key);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static SkillInvocationPolicy parseInvocation(Map<String, String> data) {
        boolean modelInvocable = !boolField(data, "disable-model-invocation", false);
        boolean userInvocable = boolField(data, "user-invocable", true);
        return new SkillInvocationPolicy(modelInvocable, userInvocable);
    }

    private static boolean boolField(Map<String, String> data, String key, boolean fallback) {
        String v = data.get(key);
        if (v == null) return fallback;
        return switch (v.toLowerCase()) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> fallback;
        };
    }

    private record ParsedSkill(String name, String description, Optional<String> whenToUse,
                                SkillInvocationPolicy invocation, String directory,
                                String path, String content) {}
    private record FrontMatter(Map<String, String> data, String body) {}
}
