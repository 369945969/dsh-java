package com.deepseek.dsh.capability.shell.tool;

import java.util.Map;

import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * PowerShell 工具 —— 对应原 Harness 的 {@code tool-pwsh}。
 *
 * <p>模型面向的 pwsh 工具，行为镜像 {@link BashTool}：前台执行 + 后台任务管理。
 * 适用于 Windows 组合，原生 {@code C:\...} 路径 + {@code $env:NAME} 变量。
 *
 * <p>设计模式：命令（Command）—— 镜像 BashTool 的调用结构。
 */
public final class PwshTool extends AbstractTool {

    private final ShellCapability shell;

    public PwshTool(ShellCapability shell) {
        this.shell = shell;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("pwsh", "执行一条 PowerShell 命令并返回输出。")
                .string("command", "要执行的 PowerShell 命令", true)
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
