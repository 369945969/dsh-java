package com.deepseek.dsh.capability.fs.tool;

import java.nio.file.Path;

import com.deepseek.dsh.capability.fs.FsCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * grep 内容搜索工具 —— 对应原 Harness 的 grep。
 */
public final class GrepTool extends AbstractTool {

    private final FsCapability fs;

    public GrepTool(FsCapability fs) {
        this.fs = fs;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("grep", "按正则在文件内容中搜索。")
                .string("pattern", "正则表达式", true)
                .string("path", "搜索基准目录", true)
                .string("include", "文件名 glob 过滤（可选）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String pattern = args.requiredString("pattern");
        Path base = Path.of(args.requiredString("path"));
        String include = args.optionalString("include", null);
        var matches = fs.grep(pattern, base, include);
        return matches.isEmpty() ? "（无匹配）" : String.join("\n", matches);
    }
}
