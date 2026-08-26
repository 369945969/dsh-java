package com.deepseek.dsh.capability.fs.tool;

import java.nio.file.Path;

import com.deepseek.dsh.capability.fs.FsCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * glob 文件搜索工具 —— 对应原 Harness 的 {@code tool-fs-search} glob。
 */
public final class GlobTool extends AbstractTool {

    private final FsCapability fs;

    public GlobTool(FsCapability fs) {
        this.fs = fs;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("glob", "按 glob 模式列出匹配文件。")
                .string("pattern", "glob 模式，如 **/*.java", true)
                .string("path", "搜索基准目录", true)
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String pattern = args.requiredString("pattern");
        Path base = Path.of(args.requiredString("path"));
        var matches = fs.glob(pattern, base);
        return matches.isEmpty() ? "（无匹配）" : String.join("\n", matches);
    }
}
