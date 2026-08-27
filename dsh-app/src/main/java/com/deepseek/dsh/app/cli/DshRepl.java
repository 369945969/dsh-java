package com.deepseek.dsh.app.cli;

import java.nio.file.Path;
import java.util.Scanner;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.app.bundle.BaseBundle;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.util.PluginRunner;
import com.deepseek.dsh.llm.meter.TokenMeterService;

/**
 * CLI 交互式入口 —— 对应原 Harness 的 {@code dsh}（默认交互终端 REPL）。
 *
 * <p>从 stdin 逐行读取用户输入，驱动 agent 对话，回复打印到 stdout。
 * 支持 {@code /exit}（或 {@code /quit}）退出、{@code /tokens} 查看累计用量、
 * {@code /new} 开启新会话。会话跨多轮复用，保持上下文记忆。
 *
 * <p>启动：{@code java com.deepseek.dsh.app.cli.DshRepl}；环境变量
 * {@code DEEPSEEK_API_KEY} / {@code DSH_BASE_URL} / {@code DSH_MODEL} 配置模型。
 *
 * <p>设计模式：命令（斜杠命令分发）+ 模板方法（read-eval-print 循环）。
 */
public final class DshRepl {

    private static final Logger log = LoggerFactory.getLogger(DshRepl.class);

    private final Context context;
    private final Agent agent;
    private SessionId sessionId;

    public DshRepl(Context context, Agent agent) {
        this.context = context;
        this.agent = agent;
        this.sessionId = SessionId.of(UUID.randomUUID().toString());
    }

    /** 运行 read-eval-print 循环直到 /exit 或 EOF。 */
    public void runLoop() {
        System.out.println("DeepSeek Harness (dsh) interactive terminal — type /exit to quit, /new for new session, /tokens to view usage");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) break;
            String line = in.nextLine();
            if (line.isBlank()) continue;

            String trimmed = line.trim();
            if (trimmed.equals("/exit") || trimmed.equals("/quit")) {
                System.out.println("Goodbye");
                break;
            }
            if (trimmed.equals("/new")) {
                sessionId = SessionId.of(UUID.randomUUID().toString());
                System.out.println("New session started: " + sessionId.value());
                continue;
            }
            if (trimmed.equals("/tokens")) {
                long total = context.get(TokenMeterService.class)
                        .map(TokenMeterService::totalTokens).orElse(0L);
                System.out.println("Cumulative token usage: " + total);
                continue;
            }

            try {
                String reply = agent.run(sessionId, ScopeKey.random(), context, trimmed);
                System.out.println(reply);
            } catch (Exception e) {
                log.warn("Conversation turn failed: {}", e.toString());
                System.out.println("(Execution failed: " + e.getMessage() + ")");
            }
        }
    }

    /** 入口：装配插件树并启动交互循环。 */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("DSH_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("DSH_MODEL", "deepseek-chat");
        Path dataDir = Path.of(System.getenv().getOrDefault("DSH_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".dsh").toString()));

        log.info("Starting CLI interactive mode: model={}, baseUrl={}", model, baseUrl);
        Context context = Context.root();
        PluginRunner runner = new PluginRunner();
        Agent agent = new BaseBundle(apiKey, baseUrl, model, dataDir).assemble(context, runner);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Unloading plugin tree...");
            runner.stop();
            context.dispose();
        }));
        new DshRepl(context, agent).runLoop();
        runner.stop();
        context.dispose();
    }
}
