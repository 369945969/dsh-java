package com.deepseek.dsh.capability.sandbox;

import java.io.File;
import java.util.Map;

import com.deepseek.dsh.core.process.ExecutionResult;
import com.deepseek.dsh.core.process.ProcessRunner;

/**
 * 本地沙箱提供者 —— 对应原 Harness 的 {@code sandbox-local}。
 *
 * <p>按操作系统自动选择隔离后端：
 * <ul>
 *   <li><b>Linux</b>：优先 Bubblewrap(bwrap)，降级为无沙箱（记录警告）。</li>
 *   <li><b>macOS</b>：Seatbelt(sandbox-exec) 生成临时 profile。</li>
 *   <li><b>Windows</b>：受限令牌（降级为无沙箱）。</li>
 * </ul>
 *
 * <p>注意：Landlock 原项目用原生 Node addon，Java 版暂用 bwrap 近似。
 * 真实生产应结合 JNI/Panama 调用 Landlock 系统调用。
 *
 * <p>设计模式：策略（按 OS 分派）+ 工厂方法。
 */
public final class LocalSandboxProvider implements SandboxCapability {

    private final String osName = System.getProperty("os.name", "").toLowerCase();

    @Override
    public ExecutionResult execute(String[] command, Map<String, String> environment,
                                   String workingDir, int timeoutSeconds, SandboxPolicy policy) {
        String[] sandboxedCommand = wrapWithSandbox(command, workingDir, policy);
        return ProcessRunner.run(sandboxedCommand, environment, workingDir, timeoutSeconds, "sandbox");
    }

    /**
     * 根据操作系统将原始命令包装为沙箱执行命令。
     */
    private String[] wrapWithSandbox(String[] command, String workingDir, SandboxPolicy policy) {
        if (osName.contains("linux")) {
            return wrapWithBwrap(command, workingDir, policy);
        }
        if (osName.contains("mac")) {
            return wrapWithSeatbelt(command, workingDir, policy);
        }
        // Windows / 其他：暂无沙箱，直接执行（记录策略于调用方）
        return command;
    }

    /**
     * Linux Bubblewrap 包装。
     * <p>bwrap 在受限命名空间内执行命令，限制文件系统与网络。
     */
    private String[] wrapWithBwrap(String[] command, String workingDir, SandboxPolicy policy) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("bwrap");
        cmd.add("--ro-bind"); cmd.add("/"); cmd.add("/");
        cmd.add("--dev"); cmd.add("/dev");
        cmd.add("--proc"); cmd.add("/proc");
        // 允许写路径
        for (String path : policy.allowWritePaths()) {
            cmd.add("--bind"); cmd.add(path); cmd.add(path);
        }
        // 工作目录
        if (workingDir != null) {
            cmd.add("--chdir"); cmd.add(workingDir);
        }
        // 网络限制
        if (!policy.allowNetwork()) {
            cmd.add("--unshare-net");
        }
        cmd.add("--");
        cmd.addAll(java.util.Arrays.asList(command));
        return cmd.toArray(new String[0]);
    }

    /**
     * macOS Seatbelt 包装。
     * <p>用 sandbox-exec + 临时 plist profile 限制文件/网络。
     */
    private String[] wrapWithSeatbelt(String[] command, String workingDir, SandboxPolicy policy) {
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n(deny default)\n");
        profile.append("(allow process-fork)\n(allow signal (target self))\n");
        // 允许读
        profile.append("(allow file-read*)\n");
        // 允许写路径
        for (String path : policy.allowWritePaths()) {
            profile.append("(allow file-write* (subpath \"")
                    .append(path).append("\"))\n");
        }
        // 网络
        if (policy.allowNetwork()) {
            profile.append("(allow network*)\n");
        }
        // 执行命令
        String cmdStr = String.join(" ", java.util.Arrays.asList(command));
        profile.append("(allow process-exec (path \"")
                .append(command[0]).append("\"))\n");

        // 写临时 profile 文件
        try {
            File tmp = File.createTempFile("dsh-sandbox-", ".plist");
            tmp.deleteOnExit();
            java.nio.file.Files.writeString(tmp.toPath(), profile.toString());
            return new String[]{"sandbox-exec", "-f", tmp.getAbsolutePath(), "--", cmdStr};
        } catch (Exception e) {
            return command; // 降级
        }
    }
}
