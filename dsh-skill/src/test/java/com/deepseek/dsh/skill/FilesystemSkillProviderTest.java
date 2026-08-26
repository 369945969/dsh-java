package com.deepseek.dsh.skill;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件系统技能提供者测试 —— frontmatter 解析 + 目录/扁平发现。
 */
class FilesystemSkillProviderTest {

    private static final String SKILL_FILE = """
            ---
            name: my-skill
            description: 测试技能
            whenToUse: 需要时
            ---
            # 指令体
            执行这些步骤。
            """;

    @Test
    void 发现并解析扁平md技能(@TempDir Path root) throws Exception {
        Path skillsDir = root.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("my-skill.md"), SKILL_FILE);

        var provider = new FilesystemSkillProvider(java.util.List.of(skillsDir.toString()));
        var candidates = provider.list(null);
        assertEquals(1, candidates.size());
        var c = candidates.get(0);
        assertEquals("my-skill", c.name());
        assertEquals("测试技能", c.description());
        assertEquals("需要时", c.whenToUse().orElseThrow());

        var def = provider.get(c).orElseThrow();
        assertTrue(def.content().startsWith("# 指令体"));
        assertTrue(def.resourceBase().isPresent());
    }

    @Test
    void 目录捆绑技能读SKILL_md(@TempDir Path root) throws Exception {
        Path skillsDir = root.resolve("skills");
        Path bundle = Files.createDirectories(skillsDir.resolve("bundle-skill"));
        Files.writeString(bundle.resolve("SKILL.md"), SKILL_FILE.replace("my-skill", "bundle-skill"));

        var provider = new FilesystemSkillProvider(java.util.List.of(skillsDir.toString()));
        var candidates = provider.list(null);
        assertEquals(1, candidates.size());
        assertEquals("bundle-skill", candidates.get(0).name());
    }

    @Test
    void 缺少name的文件被忽略(@TempDir Path root) throws Exception {
        Path skillsDir = root.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("bad.md"), "---\ndescription: 无名\n---\nbody\n");
        var provider = new FilesystemSkillProvider(java.util.List.of(skillsDir.toString()));
        assertTrue(provider.list(null).isEmpty());
    }

    @Test
    void 不存在的根返回空() {
        var provider = new FilesystemSkillProvider(java.util.List.of("/no/such/dir/xyz"));
        assertTrue(provider.list(null).isEmpty());
    }
}
