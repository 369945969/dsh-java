package com.deepseek.dsh.app.cli;

/**
 * ANSI 颜色输出开关 —— {@link DshRepl} 与 {@link CliTurnObserver} 共用。
 *
 * <p>判定规则：
 * <ul>
 *   <li>{@code NO_COLOR} 环境变量存在 → 关（遵循 https://no-color.org/）</li>
 *   <li>{@code DSH_COLOR} / {@code CLICOLOR_FORCE} / {@code FORCE_COLOR} 存在 → 开</li>
 *   <li>stdout 被重定向（无 {@link System#console()}）→ 关（纯文本，便于管道）</li>
 *   <li>Windows → 关（PowerShell 5.1 / 旧 conhost 默认不开 VT，会把 {@code \033[36m} 当文本显示成乱码）</li>
 *   <li>Unix 交互式 → 开（终端原生支持 ANSI）</li>
 * </ul>
 * 想在 Windows VT 终端（Windows Terminal / 新 cmd）开颜色：{@code set DSH_COLOR=1}。
 */
final class CliColors {

    static final boolean ON = compute();

    private static boolean compute() {
        if (System.getenv("NO_COLOR") != null) return false;
        if (System.getenv("DSH_COLOR") != null
                || System.getenv("CLICOLOR_FORCE") != null
                || System.getenv("FORCE_COLOR") != null) return true;
        if (System.console() == null) return false;
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private CliColors() {}
}
