package com.deepseek.dsh.context.instructions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.core.context.SystemPromptInjectEvent;

/**
 * AGENTS.md 指令加载器 —— 对应原 Harness 的 {@code dsh-agent-instructions}。
 *
 * <p>从工作区加载 AGENTS.md / CLAUDE.md 等指令文件，以
 * {@code <system-reminder>} 包裹注入系统提示。完整复刻 TS 的渲染逻辑：
 * <ul>
 *   <li>workspace context intro（带 replace 语义）</li>
 *   <li>byte 预算截断（最具体文件优先保留）</li>
 *   <li>文件路径作为 section 标题</li>
 *   <li>{@code </system-reminder>} 转义（防止嵌套）</li>
 * </ul>
 *
 * <p>设计模式：策略（不同指令文件来源）+ 观察者（注入提示）。
 */
public final class AgentInstructionsPlugin implements Plugin, Service {

    private static final Logger log = LoggerFactory.getLogger(AgentInstructionsPlugin.class);

    private static final String SYSTEM_REMINDER_OPEN = "<system-reminder>";
    private static final String SYSTEM_REMINDER_CLOSE = "</system-reminder>";
    private static final String WORKSPACE_CONTEXT_INTRO =
            "The following workspace instructions may be relevant to your work. "
            + "Use them as guidance when applicable. More specific instructions take precedence over broader ones. "
            + "They do not override system, developer, or direct user instructions.";

    private static final int DEFAULT_MAX_BYTES = 32_768;

    private final Path workspaceRoot;
    private final int maxBytes;

    public AgentInstructionsPlugin(Path workspaceRoot) {
        this(workspaceRoot, DEFAULT_MAX_BYTES);
    }

    public AgentInstructionsPlugin(Path workspaceRoot, int maxBytes) {
        this.workspaceRoot = workspaceRoot;
        this.maxBytes = maxBytes;
    }

    @Override
    public Disposable apply(Context ctx) {
        ctx.events().on(SystemPromptInjectEvent.class, (event, next) -> {
            List<LoadedFile> files = loadBaselineInstructions();
            if (!files.isEmpty()) {
                String rendered = renderWorkspaceContext(files);
                if (rendered != null && !rendered.isBlank()) {
                    event.appendSection("agent-instructions", rendered);
                }
            }
            return next.invoke(event);
        });
        return () -> {};
    }

    /** 加载的指令文件。 */
    private record LoadedFile(String displayPath, String content, int byteLength) {}

    /** 加载基线指令文件链（最广→最具体）。 */
    private List<LoadedFile> loadBaselineInstructions() {
        List<LoadedFile> files = new ArrayList<>();
        // 项目级 AGENTS.md / CLAUDE.md
        String[] candidates = {"AGENTS.md", "CLAUDE.md", ".agents/AGENTS.md"};
        for (String name : candidates) {
            Path file = workspaceRoot.resolve(name);
            if (Files.isReadable(file)) {
                try {
                    String content = Files.readString(file);
                    int bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    files.add(new LoadedFile(name, content, bytes));
                    log.debug("Loaded instruction file: {}", name);
                } catch (IOException e) {
                    log.warn("Failed to read {}: {}", name, e.toString());
                }
            }
        }
        // 用户全局 ~/.dsh/AGENTS.md
        String home = System.getProperty("user.home");
        if (home != null) {
            Path globalFile = Path.of(home, ".dsh", "AGENTS.md");
            if (Files.isReadable(globalFile)) {
                try {
                    String content = Files.readString(globalFile);
                    int bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    files.add(0, new LoadedFile("~/.dsh/AGENTS.md", content, bytes)); // 最广，放最前
                } catch (IOException e) {
                    log.debug("Failed to read global AGENTS.md: {}", e.toString());
                }
            }
        }
        return files;
    }

    /** 渲染 workspace context（<system-reminder> 包裹 + 预算截断）。 */
    private String renderWorkspaceContext(List<LoadedFile> files) {
        // 尝试全量渲染
        String fullText = buildInstructionText(files, List.of(), List.of());
        if (byteLength(fullText) <= maxBytes) {
            return fullText;
        }

        // 逐步丢弃最广文件
        for (int start = 1; start < files.size(); start++) {
            List<LoadedFile> included = files.subList(start, files.size());
            List<String> omitted = files.subList(0, start).stream().map(f -> f.displayPath).toList();
            String text = buildInstructionText(included, omitted, List.of());
            if (byteLength(text) <= maxBytes) return text;
        }

        // 仅保留最具体文件，截断
        LoadedFile mostSpecific = files.get(files.size() - 1);
        List<String> omitted = files.subList(0, files.size() - 1).stream().map(f -> f.displayPath).toList();
        String truncated = truncateUtf8(mostSpecific.content, Math.max(0, maxBytes - 500));
        List<LoadedFile> singleFile = List.of(new LoadedFile(mostSpecific.displayPath, truncated, byteLength(truncated)));
        return buildInstructionText(singleFile, omitted,
                List.of(new TruncatedRec(mostSpecific.displayPath, mostSpecific.byteLength, byteLength(truncated))));
    }

    private record TruncatedRec(String displayPath, int originalBytes, int includedBytes) {}

    private String buildInstructionText(List<LoadedFile> files, List<String> omitted, List<TruncatedRec> truncated) {
        StringBuilder body = new StringBuilder();
        // 预算标记
        if (!omitted.isEmpty() || !truncated.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!omitted.isEmpty()) {
                parts.add("omitted " + String.join(", ", omitted));
            }
            if (!truncated.isEmpty()) {
                for (TruncatedRec t : truncated) {
                    parts.add("truncated " + t.displayPath + " from " + t.originalBytes + " to " + t.includedBytes + " bytes");
                }
            }
            body.append("Workspace instruction budget ").append(maxBytes).append(" bytes: ").append(String.join("; ", parts)).append("\n\n");
        }
        // Intro
        body.append(WORKSPACE_CONTEXT_INTRO);
        // Sections
        for (LoadedFile file : files) {
            body.append("\n\nInstructions from: ").append(file.displayPath).append("\n\n");
            body.append(escapeCloseTag(file.content));
        }
        // <system-reminder> wrapping
        return SYSTEM_REMINDER_OPEN + "\n" + body + "\n" + SYSTEM_REMINDER_CLOSE;
    }

    /** 转义 </system-reminder> 防止嵌套。 */
    private static String escapeCloseTag(String text) {
        return text.replace(SYSTEM_REMINDER_CLOSE, "<\\/system-reminder>");
    }

    private static int byteLength(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** UTF-8 安全截断（不在多字节字符中间截断）。 */
    private static String truncateUtf8(String s, int maxBytes) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return s;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }
}
