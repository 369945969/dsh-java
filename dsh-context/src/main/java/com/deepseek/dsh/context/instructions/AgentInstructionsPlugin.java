package com.deepseek.dsh.context.instructions;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * AGENTS.md 指令加载器 —— 对应原 Harness 的 {@code agent-instructions}。
 *
 * <p>从工作区加载 AGENTS.md / CLAUDE.md 等指令文件，注入为系统提示的一部分。
 * 让 agent 了解项目约定与约束。
 *
 * <p>设计模式：策略（不同指令文件来源）+ 观察者（注入提示）。
 */
public final class AgentInstructionsPlugin implements Plugin, Service {

    private static final Logger log = LoggerFactory.getLogger(AgentInstructionsPlugin.class);

    private final Path workspaceRoot;

    public AgentInstructionsPlugin(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public Disposable apply(Context ctx) {
        ctx.events().on(SystemPromptInjectEvent.class, (event, next) -> {
            String instructions = loadInstructions();
            if (instructions != null && !instructions.isBlank()) {
                event.appendSection("agent-instructions", instructions);
            }
            return next.invoke(event);
        });
        return () -> {};
    }

    /** 加载指令文件内容。 */
    private String loadInstructions() {
        String[] candidates = {"AGENTS.md", "CLAUDE.md", ".agents/AGENTS.md"};
        StringBuilder sb = new StringBuilder();
        for (String name : candidates) {
            Path file = workspaceRoot.resolve(name);
            if (Files.isReadable(file)) {
                try {
                    String content = Files.readString(file);
                    sb.append("--- ").append(name).append(" ---\n").append(content).append("\n\n");
                    log.debug("Loaded instruction file: {}", name);
                } catch (Exception e) {
                    log.warn("Failed to read {}: {}", name, e.toString());
                }
            }
        }
        return sb.toString();
    }
}
