package com.deepseek.dsh.skill;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * {@link SkillCatalogPlugin} 单测 —— 验证 model-invocable 技能摘要被注入系统提示，
 * 非 model-invocable 技能被排除，无技能时不注入。
 */
class SkillCatalogPluginTest {

    @Test
    void injectsCatalogForModelInvocableSkills(@TempDir Path tmp) throws Exception {
        Path skillsDir = tmp.resolve("skills");
        Files.createDirectories(skillsDir.resolve("greeter"));
        Files.writeString(skillsDir.resolve("greeter").resolve("SKILL.md"),
                "---\nname: greeter\ndescription: Greets the user.\n---\nSay hi.\n");

        SkillRegistry skills = new SkillRegistry();
        skills.registerProvider(new FilesystemSkillProvider(null, List.of(), tmp.toString()));
        Context ctx = Context.root();
        new SkillCatalogPlugin(skills).apply(ctx);

        SystemPromptInjectEvent event = new SystemPromptInjectEvent();
        ctx.events().waterfall(SystemPromptInjectEvent.class, event);

        String composed = event.compose();
        assertTrue(composed.contains("<available_skills>"), "uses available_skills envelope");
        assertTrue(composed.contains("greeter"), "catalog lists skill name");
        assertTrue(composed.contains("Greets the user."), "catalog lists skill description");
        assertTrue(composed.contains("`skill` tool"), "instructs to call the skill tool");
    }

    @Test
    void omitsNonModelInvocableSkills(@TempDir Path tmp) throws Exception {
        Path skillsDir = tmp.resolve("skills");
        Files.createDirectories(skillsDir.resolve("hidden"));
        Files.writeString(skillsDir.resolve("hidden").resolve("SKILL.md"),
                "---\nname: hidden\ndescription: Secret.\ndisable-model-invocation: true\n---\nbody\n");

        SkillRegistry skills = new SkillRegistry();
        skills.registerProvider(new FilesystemSkillProvider(null, List.of(), tmp.toString()));
        Context ctx = Context.root();
        new SkillCatalogPlugin(skills).apply(ctx);

        SystemPromptInjectEvent event = new SystemPromptInjectEvent();
        ctx.events().waterfall(SystemPromptInjectEvent.class, event);

        assertTrue(event.compose().isBlank(), "non-model-invocable skills are not listed");
        assertTrue(!event.compose().contains("hidden"), "hidden skill name must not appear");
    }

    @Test
    void emptyWhenNoSkills(@TempDir Path tmp) {
        SkillRegistry skills = new SkillRegistry();
        skills.registerProvider(new FilesystemSkillProvider(null, List.of(), tmp.toString()));
        Context ctx = Context.root();
        new SkillCatalogPlugin(skills).apply(ctx);

        SystemPromptInjectEvent event = new SystemPromptInjectEvent();
        ctx.events().waterfall(SystemPromptInjectEvent.class, event);
        assertTrue(event.compose().isBlank(), "no skills -> no catalog section");
    }
}
