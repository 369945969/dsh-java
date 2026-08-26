package com.deepseek.dsh.capability.fs.tool;

import java.nio.file.Path;

import com.deepseek.dsh.capability.fs.FsCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 精确字符串替换编辑工具 —— 对应原 Harness 的 str-replace-editor。
 *
 * <p>通过提供 oldString（唯一匹配）和 newString 进行精确替换。
 */
public final class EditTool extends AbstractTool {

    private final FsCapability fs;

    public EditTool(FsCapability fs) {
        this.fs = fs;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("edit", "精确字符串替换编辑。")
                .string("path", "文件绝对路径", true)
                .string("oldString", "要替换的原文（须唯一）", true)
                .string("newString", "替换为的新文本", true)
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        Path path = Path.of(args.requiredString("path"));
        String oldStr = args.requiredString("oldString");
        String newStr = args.requiredString("newString");
        fs.edit(path, oldStr, newStr);
        return "已编辑 " + path;
    }
}
