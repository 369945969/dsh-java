package com.deepseek.dsh.capability.fs.tool;

import java.nio.file.Path;

import com.deepseek.dsh.capability.fs.FsCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 文件写入工具 —— 对应原 Harness 的 write。
 */
public final class WriteTool extends AbstractTool {

    private final FsCapability fs;

    public WriteTool(FsCapability fs) {
        this.fs = fs;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("write", "写入文件（覆盖）。")
                .string("path", "文件绝对路径", true)
                .string("content", "写入内容", true)
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        Path path = Path.of(args.requiredString("path"));
        String content = args.requiredString("content");
        fs.write(path, content);
        return "已写入 " + path;
    }
}
