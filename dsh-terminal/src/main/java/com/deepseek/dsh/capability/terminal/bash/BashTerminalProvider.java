package com.deepseek.dsh.capability.terminal.bash;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.capability.terminal.TerminalCapability;

/**
 * bash 持久终端提供者 —— 对应原 Harness 的 {@code terminal-bash}。
 *
 * <p>启动一个持久 bash 进程，通过其 stdin/stdout 维持跨调用的会话状态。
 * 每次 {@link #send} 写入命令并读取到分隔符为止的输出。
 *
 * <p>简化实现：用唯一哨兵分隔符标记命令结束，据此切分输出。
 * 真实 PTY 需 {@code node-pty} 等伪终端支持（此处用进程流近似）。
 *
 * <p>设计模式：策略的具体实现。
 */
public final class BashTerminalProvider implements TerminalCapability {

    private static final Logger log = LoggerFactory.getLogger(BashTerminalProvider.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(0);

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static String[] resolveShell() {
        if (!WINDOWS) {
            return new String[]{"bash", "--norc", "-i"};
        }
        String pwsh = findOnPath("pwsh");
        if (pwsh == null) {
            String pf = System.getenv("ProgramFiles");
            if (pf != null) {
                String candidate = pf + "\\PowerShell\\7\\pwsh.exe";
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(candidate))) {
                    pwsh = candidate;
                }
            }
        }
        if (pwsh != null) {
            return new String[]{pwsh, "-NoProfile", "-NoLogo", "-NoExit", "-Command", "-"};
        }
        return new String[]{"powershell", "-NoProfile", "-NoLogo", "-NoExit", "-Command", "-"};
    }

    private static String findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String exe = WINDOWS ? name + ".exe" : name;
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(dir, exe);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
    }

    @Override
    public String createSession(String cwd) {
        String id = "term-" + idSeq.incrementAndGet();
        try {
            String[] shell = resolveShell();
            ProcessBuilder pb = new ProcessBuilder(shell);
            pb.redirectErrorStream(true);
            if (cwd != null) pb.directory(new java.io.File(cwd));
            Process process = pb.start();
            sessions.put(id, new Session(process));
            log.debug("Creating terminal session: {}", id);
            return id;
        } catch (Exception e) {
            throw new RuntimeException("Cannot create terminal session: " + e.getMessage(), e);
        }
    }

    @Override
    public TerminalOutput send(String sessionId, String input, int timeoutSeconds) {
        Session s = sessions.get(sessionId);
        if (s == null) return new TerminalOutput("(Terminal not found: " + sessionId + ")", true);
        return s.send(input, timeoutSeconds);
    }

    @Override
    public TerminalOutput read(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null) return TerminalOutput.eof();
        return s.drainOutput();
    }

    @Override
    public void destroy(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s != null) s.destroy();
    }

    @Override
    public List<String> listSessions() {
        return List.copyOf(sessions.keySet());
    }

    /** 单个持久会话。 */
    private static final class Session {
        private final Process process;
        private final OutputStream stdin;
        private final java.io.InputStream stdout;
        private final StringBuilder buffer = new StringBuilder();
        private final String sentinel = "__DSH_EOF_" + System.nanoTime() + "__";

        Session(Process process) {
            this.process = process;
            this.stdin = process.getOutputStream();
            this.stdout = process.getInputStream();
            // 后台线程持续读取输出到缓冲
            Thread reader = new Thread(this::readLoop, "terminal-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void readLoop() {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = stdout.read(buf)) != -1) {
                    synchronized (buffer) {
                        buffer.append(new String(buf, 0, n));
                        buffer.notifyAll();
                    }
                }
            } catch (Exception e) {
                log.debug("Terminal read ended: {}", e.toString());
            }
        }

        TerminalOutput send(String input, int timeoutSeconds) {
            try {
                String cmd = input + "\n echo '" + sentinel + "'\n";
                stdin.write(cmd.getBytes());
                stdin.flush();
                // 等待哨兵出现
                long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
                synchronized (buffer) {
                    while (!buffer.toString().contains(sentinel)) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) break;
                        buffer.wait(remaining);
                    }
                    String all = buffer.toString();
                    int idx = all.indexOf(sentinel);
                    String output;
                    if (idx >= 0) {
                        output = all.substring(0, idx);
                        buffer.replace(0, all.length(), all.substring(idx + sentinel.length()));
                    } else {
                        output = all;
                        buffer.setLength(0);
                    }
                    return TerminalOutput.of(output);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TerminalOutput.of("（超时）");
            } catch (Exception e) {
                return TerminalOutput.of("（发送失败: " + e.getMessage() + "）");
            }
        }

        TerminalOutput drainOutput() {
            synchronized (buffer) {
                String out = buffer.toString();
                buffer.setLength(0);
                return TerminalOutput.of(out);
            }
        }

        void destroy() {
            try {
                stdin.close();
            } catch (Exception ignored) {
            }
            process.destroyForcibly();
        }
    }
}
