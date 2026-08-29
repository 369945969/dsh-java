package com.deepseek.dsh.capability.shell.local;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.capability.shell.ShellResult;
import com.deepseek.dsh.core.process.ExecutionResult;
import com.deepseek.dsh.core.process.ProcessRunner;

/**
 * 本地 shell 提供者 —— 对应原 Harness 的 {@code dsh-bash-local}。
 *
 * <p>按操作系统自动选择解释器：
 * <ul>
 *   <li><b>Windows</b>：优先 {@code pwsh}（PowerShell 7，默认 UTF-8 输出），
 *       次选 {@code powershell}（5.1），保证中文不乱码。</li>
 *   <li><b>Unix/macOS</b>：{@code bash -c}（原行为不变）。</li>
 * </ul>
 *
 * <p><b>重构后</b>：进程启动/输出排空/超时逻辑全部委托给共用的 {@link ProcessRunner}，
 * 本类仅负责命令组装与结果映射，消除此前逐字节重复的 drain 样板。
 *
 * <p>设计模式：策略的具体实现（委托给 ProcessRunner 工具）。
 */
public final class BashLocalProvider implements ShellCapability {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String[] SHELL_CMD = resolveShell();
    private static final String SHELL_NAME = shortName(SHELL_CMD[0]);

    @Override
    public String shellName() {
        return SHELL_NAME;
    }

    @Override
    public ShellResult execute(String command, Map<String, String> env, String cwd, int timeoutSeconds) {
        String[] cmd = new String[SHELL_CMD.length + 1];
        System.arraycopy(SHELL_CMD, 0, cmd, 0, SHELL_CMD.length);
        cmd[cmd.length - 1] = command;
        // 模型友好的环境：禁用颜色/进度条/ANSI，保证输出为纯文本（镜像 PwshLocalProvider）
        Map<String, String> childEnv = new java.util.HashMap<>(env);
        childEnv.put("NO_COLOR", "1");
        childEnv.put("TERM", "dumb");
        ExecutionResult result = ProcessRunner.run(cmd, childEnv, cwd, timeoutSeconds, "shell");
        return toShellResult(result);
    }

    /** {@link ExecutionResult} → {@link ShellResult}（保持外部 API 不变）。 */
    private static ShellResult toShellResult(ExecutionResult r) {
        return new ShellResult(r.stdout(), r.stderr(), r.exitCode(), r.timedOut());
    }

    private static String[] resolveShell() {
        if (!WINDOWS) {
            return new String[]{"bash", "-c"};
        }
        String pwsh = findOnPath("pwsh");
        if (pwsh == null) {
            String pf = System.getenv("ProgramFiles");
            if (pf != null) {
                String candidate = pf + "\\PowerShell\\7\\pwsh.exe";
                if (Files.exists(Paths.get(candidate))) {
                    pwsh = candidate;
                }
            }
        }
        if (pwsh != null) {
            return new String[]{pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"};
        }
        return new String[]{"powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"};
    }

    private static String findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String exe = WINDOWS ? name + ".exe" : name;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, exe);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
    }

    /** 把可执行文件路径归一成模型可见的短名（pwsh/powershell/bash）。 */
    private static String shortName(String exe) {
        if (exe == null) return "bash";
        String n = exe.toLowerCase();
        if (n.endsWith("pwsh.exe") || n.equals("pwsh")) return "pwsh";
        if (n.endsWith("powershell.exe") || n.equals("powershell")) return "powershell";
        return exe;
    }
}
