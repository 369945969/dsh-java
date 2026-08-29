package com.deepseek.dsh.capability.shell.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.capability.shell.ShellResult;
import com.deepseek.dsh.core.process.ExecutionResult;
import com.deepseek.dsh.core.process.ProcessRunner;

/**
 * 本地 PowerShell 执行提供者 —— 对应原 Harness 的 {@code dsh-pwsh-local}。
 *
 * <p>每条命令以 {@code pwsh -NoLogo -NoProfile -NonInteractive -Command <command>} 执行。
 * 命令字符串作为单个 argv 元素传给 {@code -Command}：PowerShell 自行解析文本，
 * 无中间 shell 层、无需 shell 转义。原生 Win32 路径（{@code C:\...}）原样透传。
 *
 * <p>设计模式：策略（PowerShell 具体实现）—— 镜像 {@link BashLocalProvider} 的调用结构。
 */
public final class PwshLocalProvider implements ShellCapability {

    /**
     * 解析 pwsh 可执行文件路径。
     * <ul>
     *   <li>配置了 pwshPath → 直接用</li>
     *   <li>Windows → 搜 Program Files\PowerShell\7\pwsh.exe → PATH → Windows PowerShell 5.1</li>
     *   <li>其他 → "pwsh"（PATH 解析）</li>
     * </ul>
     */
    public static String resolvePwshPath(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            // PowerShell 7
            Path pwsh7 = Path.of(programFiles, "PowerShell", "7", "pwsh.exe");
            if (Files.isExecutable(pwsh7)) return pwsh7.toString();
            // Windows PowerShell 5.1 fallback
            Path winPS = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isExecutable(winPS)) return winPS.toString();
        }
        return "pwsh";
    }

    private final String pwshPath;

    public PwshLocalProvider() {
        this(null);
    }

    public PwshLocalProvider(String pwshPath) {
        this.pwshPath = resolvePwshPath(pwshPath);
    }

    public String name() {
        return "pwsh-local";
    }

    @Override
    public ShellResult execute(String command, Map<String, String> env, String workdir, int timeoutSeconds) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add(pwshPath);
        argv.add("-NoLogo");
        argv.add("-NoProfile");
        argv.add("-NonInteractive");
        argv.add("-Command");
        argv.add(command);

        // 模型友好的环境：禁用颜色、进度条、ANSI
        Map<String, String> childEnv = new java.util.HashMap<>(env);
        childEnv.put("NO_COLOR", "1");
        childEnv.put("TERM", "dumb");
        childEnv.put("PSIdentity", "None");

        ExecutionResult r = ProcessRunner.run(argv.toArray(new String[0]), childEnv, workdir, timeoutSeconds, "pwsh");
        return new ShellResult(r.stdout(), r.stderr(), r.exitCode(), r.timedOut());
    }
}
