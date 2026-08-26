package com.deepseek.dsh.capability.terminal.tool;

import java.util.List;

import com.deepseek.dsh.capability.terminal.TerminalCapability;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * terminal 工具 —— 对应原 Harness 的 {@code tool-terminal}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractTool}，消除样板。
 */
public final class TerminalTool extends AbstractTool {

    private final TerminalCapability terminal;

    public TerminalTool(TerminalCapability terminal) {
        this.terminal = terminal;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("terminal", "管理持久终端会话（跨调用保持进程状态）。")
                .enumStr("action", List.of("create", "send", "read", "destroy", "list"),
                        "操作类型", true)
                .string("sessionId", "会话 ID（send/read/destroy 必填）")
                .string("input", "要发送的输入（send 必填）")
                .string("workdir", "工作目录（create 可选）")
                .intProp("timeout", "send 超时秒数（默认 30）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String action = args.requiredString("action");
        return switch (action) {
            case "create" -> {
                String cwd = args.optionalString("workdir", null);
                String id = terminal.createSession(cwd);
                yield "已创建终端会话: " + id;
            }
            case "send" -> {
                String id = args.requiredString("sessionId");
                String input = args.requiredString("input");
                int timeout = args.optionalInt("timeout", 30);
                var out = terminal.send(id, input, timeout);
                yield out.text().isEmpty() ? "（无输出）" : out.text();
            }
            case "read" -> {
                String id = args.requiredString("sessionId");
                var out = terminal.read(id);
                yield out.text().isEmpty() ? "（无待输出）" : out.text();
            }
            case "destroy" -> {
                String id = args.requiredString("sessionId");
                terminal.destroy(id);
                yield "已销毁终端会话: " + id;
            }
            case "list" -> {
                var ids = terminal.listSessions();
                yield ids.isEmpty() ? "（无活跃会话）" : String.join(", ", ids);
            }
            default -> "未知操作: " + action;
        };
    }
}
