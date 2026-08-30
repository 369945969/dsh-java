package com.deepseek.dsh.capability.shell.tool;

import java.util.Map;

import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * bash 工具 —— 面向模型的 shell 执行工具，对应原 Harness 的 {@code tool-bash}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractTool}，用 {@link ToolSchema.Builder} 和
 * {@link ToolArgs} 消除样板；结果用 {@link com.deepseek.dsh.core.process.ExecutionResult#combinedOutput()} 统一格式化。
 */
public final class BashTool extends AbstractTool {

    private final ShellCapability shell;

    public BashTool(ShellCapability shell) {
        this.shell = shell;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("bash", "执行一条 bash 命令并返回输出。")
                .string("command", "要执行的命令", true)
                .string("workdir", "工作目录（可选）")
                .intProp("timeout", "超时秒数（默认 120）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String command = args.requiredString("command");
        String workdir = args.optionalString("workdir", null);
        if (workdir == null) workdir = com.deepseek.dsh.core.context.SessionCwd.get();
        int timeout = args.optionalInt("timeout", 120);
        var result = shell.execute(command, Map.of(), workdir, timeout);
        return result.combinedOutput();
    }
}
