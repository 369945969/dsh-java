package com.deepseek.dsh.goal;

import com.deepseek.dsh.interaction.command.CommandRegistry;
import com.deepseek.dsh.interaction.command.Commands;

/**
 * goal 命令 —— 对应原 Harness 的 {@code /goal} 命令。
 *
 * <p>用户通过斜杠命令管理目标：设置、查询、暂停、完成、解除。
 * 命令不经模型，直接派发到 {@link Goals} 服务。
 *
 * <p>设计模式：命令（Command）。
 */
public final class GoalCommand {

    private GoalCommand() {}

    /** 注册 /goal 命令到命令注册表。 */
    public static void register(Commands commands, Goals goals) {
        if (commands instanceof CommandRegistry reg) {
            reg.register("goal", args -> handleGoal(args, goals));
        }
    }

    private static String handleGoal(String[] args, Goals goals) {
        if (args.length == 0) {
            return "用法: /goal <set|status|pause|complete|clear> [目标文本]";
        }
        String sub = args[0];
        return switch (sub) {
            case "set" -> {
                if (args.length < 2) yield "请提供目标文本";
                String objective = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                Goal g = goals.arm(null, objective);
                yield "目标已激活: " + g.objective();
            }
            case "status" -> {
                var cur = goals.current(null);
                yield cur.map(g -> "目标: " + g.objective()
                        + "\n阶段: " + g.phase()
                        + "\n已完成轮次: " + g.roundsCompleted()).orElse("（无活跃目标）");
            }
            case "pause" -> {
                goals.setPhase(null, GoalPhase.PAUSED);
                yield "目标已暂停";
            }
            case "complete" -> {
                goals.setPhase(null, GoalPhase.COMPLETE);
                yield "目标已完成";
            }
            case "clear" -> {
                goals.disarm(null);
                yield "目标已解除";
            }
            default -> "未知子命令: " + sub;
        };
    }
}
