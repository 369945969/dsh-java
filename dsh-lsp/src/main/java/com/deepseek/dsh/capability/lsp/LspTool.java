package com.deepseek.dsh.capability.lsp;

import java.util.stream.Collectors;

import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * lsp 工具 —— 对应原 Harness 的 {@code tool-lsp}。
 *
 * <p>模型面向的语言服务器工具：定义跳转、引用查询、诊断。
 * 通过注入的 {@link LspCapability} 能力缝执行。
 *
 * <p>设计模式：命令（Command）+ 模板方法。
 */
public final class LspTool extends AbstractTool {

    private final LspCapability lsp;
    private final String serverId;

    public LspTool(LspCapability lsp, String serverId) {
        this.lsp = lsp;
        this.serverId = serverId;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("lsp", "语言服务器操作（定义跳转/诊断）。")
                .enumStr("action", java.util.List.of("definition", "diagnostics"),
                        "操作类型", true)
                .string("file", "文件路径", true)
                .intProp("line", "行号（0-based）")
                .intProp("character", "列号（0-based）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String action = args.requiredString("action");
        String file = args.requiredString("file");
        return switch (action) {
            case "definition" -> {
                int line = args.optionalInt("line", 0);
                int ch = args.optionalInt("character", 0);
                var locs = lsp.findDefinitions(serverId, file, line, ch);
                yield locs.isEmpty() ? "（未找到定义）"
                        : locs.stream()
                        .map(l -> l.filePath() + ":" + l.line() + ":" + l.character())
                        .collect(Collectors.joining("\n"));
            }
            case "diagnostics" -> {
                var diags = lsp.diagnostics(serverId, file);
                yield diags.isEmpty() ? "（无诊断）"
                        : diags.stream()
                        .map(d -> "L" + d.line() + " [" + d.severity() + "]: " + d.message())
                        .collect(Collectors.joining("\n"));
            }
            default -> "未知操作: " + action;
        };
    }
}
