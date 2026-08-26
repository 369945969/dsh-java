package com.deepseek.dsh.core.process;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.exception.CapabilityException;

/**
 * 子进程执行器 —— 共用的进程启动/输出捕获/超时处理工具。
 *
 * <p>消除 {@code BashLocalProvider} 与 {@code PythonCodeRuntime} 中逐字节重复的
 * "启动进程 → 排空 stdout/stderr → 带超时等待 → 处理超时" 逻辑。
 *
 * <p>核心改进：
 * <ul>
 *   <li>用虚拟线程排空流（Java 21），轻量且无需手动管理线程池。</li>
 *   <li>异常不再被吞（原先 {@code sb.append(e)} 吞异常），转为
 *       {@link CapabilityException} 传播。</li>
 *   <li>try-with-resources 确保 IO 资源释放。</li>
 * </ul>
 *
 * <p>设计模式：工具类（Utility）+ 模板方法（execute 定义执行骨架）。
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    private ProcessRunner() {}

    /**
     * 执行一个命令并返回结果。
     *
     * @param command       命令数组
     * @param environment   环境变量（null 表示继承当前环境）
     * @param workingDir    工作目录（null 表示当前目录）
     * @param timeoutSeconds 超时秒数（<=0 表示不超时）
     * @param capability    能力名（用于异常上下文）
     */
    public static ExecutionResult run(String[] command, Map<String, String> environment,
                                     String workingDir, int timeoutSeconds, String capability) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        if (workingDir != null) pb.directory(new File(workingDir));
        if (environment != null) pb.environment().putAll(environment);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new CapabilityException(capability,
                    "无法启动进程: " + String.join(" ", command), e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        // 用虚拟线程排空，确保不阻塞子进程管道
        Thread outThread = Thread.startVirtualThread(
                () -> drain(process.getInputStream(), stdout, capability));
        Thread errThread = Thread.startVirtualThread(
                () -> drain(process.getErrorStream(), stderr, capability));

        try {
            boolean finished;
            if (timeoutSeconds > 0) {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    outThread.join(1000);
                    errThread.join(1000);
                    return ExecutionResult.timedOut(stdout.toString(), stderr.toString());
                }
            } else {
                process.waitFor();
                finished = true;
            }
            outThread.join();
            errThread.join();
            return ExecutionResult.of(stdout.toString(), stderr.toString(), process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CapabilityException(capability,
                    "执行被中断: " + String.join(" ", command), e);
        }
    }

    /**
     * 排空一个输入流到 StringBuilder（逐行读取）。
     * 异常转为日志并写入缓冲末尾（不再静默吞掉）。
     */
    private static void drain(InputStream is, StringBuilder sb, String capability) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        } catch (Exception e) {
            log.warn("[{}] 读取进程输出流失败: {}", capability, e.toString());
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("[读取输出流错误: ").append(e.getMessage()).append(']');
        }
    }
}
