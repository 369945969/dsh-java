package com.deepseek.dsh.capability.fs.tool;

import java.nio.file.Path;

import com.deepseek.dsh.capability.fs.FsCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 文件读取工具 —— 对应原 Harness 的 {@code tool-fs} read。
 *
 * <p><b>重构后</b>：继承 {@link AbstractTool}，用 {@link ToolSchema.Builder} 声明 schema、
 * 用 {@link ToolArgs} 类型安全提取参数，消除样板。
 *
 * <p>设计模式：命令（Command）+ 模板方法。
 */
public final class ReadTool extends AbstractTool {

    private final FsCapability fs;

    public ReadTool(FsCapability fs) {
        this.fs = fs;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("read", "读取文件内容。")
                .string("path", "文件绝对路径", true)
                .intProp("offset", "起始行号（1-based，默认 1）")
                .intProp("limit", "读取行数（默认 2000）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        Path path = Path.of(args.requiredString("path"));
        int offset = args.optionalInt("offset", 1) - 1;
        int limit = args.optionalInt("limit", 2000);
        return fs.read(path, offset, limit);
    }
}
