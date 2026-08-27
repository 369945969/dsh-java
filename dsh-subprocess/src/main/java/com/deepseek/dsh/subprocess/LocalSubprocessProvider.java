package com.deepseek.dsh.subprocess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.core.exception.CapabilityException;
import com.deepseek.dsh.core.process.ExecutionResult;
import com.deepseek.dsh.core.process.ProcessRunner;

/**
 * 本地子进程提供者 —— 对应原 Harness 的 {@code subprocess-local}。
 *
 * <p>宿主文件系统的子进程实现：复用 {@link ProcessRunner}（虚拟线程排空、
 * 带超时等待、异常转 {@link CapabilityException}）完成启动/收集/退出。
 * 环境用 {@link SubprocessService#scrubbedParentEnv()} 清洗后再叠加显式 env，
 * 凭据形名称与 {@code DSH_*} 不隐式泄漏给孩子。
 *
 * <p>设计模式：策略的具体实现 + 适配器（适配 ProcessRunner 到本缝）。
 */
public final class LocalSubprocessProvider
        extends AbstractCapabilityPlugin<SubprocessService>
        implements SubprocessService {

    private static final Logger log = LoggerFactory.getLogger(LocalSubprocessProvider.class);

    @Override
    protected Class<SubprocessService> serviceType() {
        return SubprocessService.class;
    }

    @Override
    public CompletableFuture<String> resolveExecutable(String command, Map<String, String> env) {
        return CompletableFuture.supplyAsync(() -> {
            if (command == null || command.isBlank()) {
                throw new CapabilityException("subprocess", "Command is empty", null);
            }
            // 含分隔符的相对路径：拒绝（解析基未定义）
            if (command.contains("/") || command.contains("\\")) {
                Path p = Path.of(command);
                if (!Files.isExecutable(p)) {
                    throw new CapabilityException("subprocess", "Not executable: " + command, null);
                }
                return p.toAbsolutePath().toString();
            }
            // 裸名：用清洗过的 PATH + 显式 env 解析
            Map<String, String> lookupEnv = new HashMap<>(SubprocessService.scrubbedParentEnv());
            if (env != null) lookupEnv.putAll(env);
            String path = lookupEnv.getOrDefault("PATH", System.getenv("PATH"));
            if (path == null) {
                throw new CapabilityException("subprocess", "PATH not set, cannot resolve: " + command, null);
            }
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path candidate = Path.of(dir, command);
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
            throw new CapabilityException("subprocess", "Executable not found in PATH: " + command, null);
        });
    }

    @Override
    public SubprocessHandle spawn(SubprocessSpawnSpec spec) {
        Map<String, String> env = new HashMap<>(SubprocessService.scrubbedParentEnv());
        if (spec.env() != null) env.putAll(spec.env());

        String[] command = spec.argv().toArray(new String[0]);
        CompletableFuture<SubprocessHandle> done = new CompletableFuture<>();

        // 复用 ProcessRunner：虚拟线程排空 + 带超时等待
        ExecutionResult result = ProcessRunner.run(
                command, env, spec.workingDir(), spec.timeoutSeconds(), "subprocess");

        SubprocessHandle handle = new SubprocessHandle(
                spec.collectStdout() ? result.stdout() : "",
                spec.collectStderr() ? result.stderr() : "",
                result.exitCode(),
                result.timedOut(),
                done);
        done.complete(handle);
        return handle;
    }
}
