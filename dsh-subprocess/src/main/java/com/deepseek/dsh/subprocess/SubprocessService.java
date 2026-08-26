package com.deepseek.dsh.subprocess;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.deepseek.dsh.core.context.Service;

/**
 * 子进程能力缝 —— 对应原 Harness 的 {@code ctx.subprocess}。
 *
 * <p>抽象执行世界的可执行查找、完全规格化的托管进程树（原始或收集的 stdio）
 * 与终端进程原语。命令默认、shell 语义、截止时间、协议帧、终端就绪与呈现
 * 归消费者。本地实现在 {@code LocalSubprocessProvider}。
 *
 * <p>实现须遵守的语义：
 * <ul>
 *   <li>{@link #resolveExecutable} 校验绝对路径，用清洗过的 PATH 解析裸名。</li>
 *   <li>{@link #spawn} 立即返回活动句柄；{@code done} 在进程关闭时完成，
 *       仅 spawn 级失败才异常完成。</li>
 *   <li>凭据形名称的父环境变量不隐式转发给孩子（{@link #scrubbedParentEnv}）。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface SubprocessService extends Service {

    /** 环境名清洗正则：含 KEY/PASSWORD/SECRET/TOKEN 的名不转发给孩子（大小写无关）。 */
    java.util.regex.Pattern SENSITIVE_ENV_PATTERN =
            java.util.regex.Pattern.compile("KEY|PASSWORD|SECRET|TOKEN",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** DSH 自有环境变量前缀。 */
    String DSH_ENV_PREFIX = "DSH_";

    /**
     * 解析一个配置的可执行文件。绝对路径校验；裸名用清洗过的 PATH + 显式 env。
     * 含分隔符的相对路径被拒（解析基未定义，宁可失败也不猜）。
     *
     * @param command 绝对可执行路径或裸 PATH 名
     * @param env     显式环境条目（用于查找）
     * @return 规范可执行路径；找不到时 future 异常完成
     */
    CompletableFuture<String> resolveExecutable(String command,
                                                Map<String, String> env);

    /**
     * 从完全规格启动一个托管子进程。
     *
     * @param spec argv、目录、stdio、宽限、取消与环境
     * @return 活动进程句柄
     */
    SubprocessHandle spawn(SubprocessSpawnSpec spec);

    /**
     * 当前父环境减去凭据形名称与全部 {@code DSH_*} 名 —— 每个孩子进程的安全起点。
     * PATH/HOME/locale/proxy 保留，harness 身份绝不隐式泄漏。
     */
    static Map<String, String> scrubbedParentEnv() {
        Map<String, String> env = new java.util.HashMap<>();
        System.getenv().forEach((k, v) -> {
            if (v != null
                    && !SENSITIVE_ENV_PATTERN.matcher(k).find()
                    && !k.toUpperCase().startsWith(DSH_ENV_PREFIX)) {
                env.put(k, v);
            }
        });
        return env;
    }

    /**
     * 子进程启动规格 —— 完全规格，本缝不加默认。
     *
     * @param argv        参数数组（argv[0] 为命令）
     * @param workingDir  工作目录；null 继承当前
     * @param env         显式环境（合并在清洗过的父环境之后）
     * @param collectStdout 是否收集 stdout
     * @param collectStderr 是否收集 stderr
     * @param timeoutSeconds 超时秒数；<=0 不超时
     */
    record SubprocessSpawnSpec(
            List<String> argv,
            String workingDir,
            Map<String, String> env,
            boolean collectStdout,
            boolean collectStderr,
            int timeoutSeconds
    ) {
        public SubprocessSpawnSpec {
            java.util.List<String> a = argv == null ? List.of() : List.copyOf(argv);
            argv = a;
        }
    }

    /**
     * 子进程句柄 —— 收集的输出、退出事实。
     *
     * @param stdout 收集的 stdout（收集模式；否则空）
     * @param stderr 收集的 stderr（收集模式；否则空）
     * @param exitCode 退出码（-1 表示超时被杀）
     * @param timedOut 是否超时
     * @param done     在进程关闭时完成的 future
     */
    record SubprocessHandle(
            String stdout,
            String stderr,
            int exitCode,
            boolean timedOut,
            CompletableFuture<SubprocessHandle> done
    ) {}
}
