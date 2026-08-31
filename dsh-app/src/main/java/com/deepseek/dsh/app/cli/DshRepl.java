package com.deepseek.dsh.app.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.web.TurnOrchestrator;
import com.deepseek.dsh.app.bundle.BaseBundle;
import com.deepseek.dsh.compaction.CompactionService;
import com.deepseek.dsh.compaction.BasicCompactionProvider;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.SessionCwd;
import com.deepseek.dsh.core.util.PluginRunner;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.config.ModelConfig;
import com.deepseek.dsh.llm.config.ModelProfile;
import com.deepseek.dsh.llm.config.ModelProfileStore;
import com.deepseek.dsh.llm.meter.TokenMeterService;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;

/**
 * CLI 交互式入口 —— 对应原 Harness 的 {@code dsh}（默认交互终端 REPL）。
 *
 * <p>斜杠命令（参考 Claude Code 风格）：
 * <ul>
 *   <li>{@code /help} 或 {@code ?} — 显示可用命令</li>
 *   <li>{@code /model [name]} — 列出/切换模型</li>
 *   <li>{@code /compact} — 压缩上下文（保留近期+摘要）</li>
 *   <li>{@code /new} — 新会话</li>
 *   <li>{@code /tokens} — 查看 token 用量</li>
 *   <li>{@code /exit} 或 {@code /quit} — 退出</li>
 * </ul>
 *
 * <p>设计模式：命令（斜杠命令分发）+ 模板方法（read-eval-print 循环）。
 */
public final class DshRepl {

    private static final Logger log = LoggerFactory.getLogger(DshRepl.class);

    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";
    private static final String RESET = "\033[0m";
    private static final String PROMPT = DIM + "> " + RESET;

    private static BufferedReader stdinReader;

    private final Context context;
    private final Agent agent;
    private final TurnOrchestrator orchestrator;
    private final Sessions sessions;
    private SessionId sessionId;

    public DshRepl(Context context, Agent agent) {
        this.context = context;
        this.agent = agent;
        this.sessions = context.get(Sessions.class).orElse(null);
        this.orchestrator = new TurnOrchestrator(context, agent, null);
        this.sessionId = SessionId.of(UUID.randomUUID().toString());
    }

    /** 运行 read-eval-print 循环直到 /exit 或 EOF。 */
    public void runLoop() {
        printBanner();
        while (true) {
            String line = readUserLine();
            if (line == null) break;
            if (line.isBlank()) continue;

            String trimmed = line.trim();

            // 斜杠命令
            if (trimmed.startsWith("/") || trimmed.equals("?") || trimmed.equals("？")) {
                if (handleCommand(trimmed)) break;
                continue;
            }

            // 对话
            try {
                runTurnStreamed(trimmed);
            } catch (Exception e) {
                log.warn("Conversation turn failed: {}", e.toString());
                System.out.println(YELLOW + "(Execution failed: " + e.getMessage() + ")" + RESET);
            }
        }
    }

    private static String readUserLine() {
        Console console = System.console();
        String line;
        if (console != null) {
            line = console.readLine(PROMPT);
        } else {
            System.out.print(PROMPT);
            if (stdinReader == null) {
                stdinReader = new BufferedReader(new InputStreamReader(System.in, consoleCharset()));
            }
            try {
                line = stdinReader.readLine();
            } catch (IOException e) {
                line = null;
            }
        }
        return line;
    }

    private static Charset consoleCharset() {
        String ne = System.getProperty("native.encoding");
        if (ne != null) {
            try {
                return Charset.forName(ne);
            } catch (Exception ignore) {
            }
        }
        return Charset.defaultCharset();
    }

    private boolean handleCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String name = parts[0];
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (name) {
            case "/exit", "/quit" -> {
                System.out.println(DIM + "Goodbye" + RESET);
                return true;
            }
            case "/new" -> {
                sessionId = SessionId.of(UUID.randomUUID().toString());
                System.out.println(DIM + "New session: " + sessionId.value() + RESET);
            }
            case "/sessions", "/list" -> handleSessionsCommand();
            case "/session", "/switch" -> handleSwitchCommand(args);
            case "/fork" -> handleForkCommand();
            case "/tokens" -> {
                long total = context.get(TokenMeterService.class)
                        .map(TokenMeterService::totalTokens).orElse(0L);
                System.out.println(DIM + "  Cumulative tokens: " + total + RESET);
            }
            case "/model" -> handleModelCommand(args);
            case "/compact" -> handleCompactCommand(args);
            case "/help", "?", "？" -> printHelp();
            default -> System.out.println(YELLOW + "  Unknown command: " + name + " (type /help)" + RESET);
        }
        return false;
    }

    /** /sessions — 列出全部会话（活跃 + 持久化）。 */
    private void handleSessionsCommand() {
        if (sessions == null) {
            System.out.println(YELLOW + "  Sessions service not registered" + RESET);
            return;
        }
        List<SessionId> ids = sessions.list();
        if (ids.isEmpty()) {
            System.out.println(DIM + "  (no sessions)" + RESET);
            return;
        }
        System.out.println(CYAN + "  Sessions:" + RESET);
        for (SessionId id : ids) {
            SessionLog sl = sessions.getOrCreate(id);
            String title = TurnOrchestrator.generateTitle(sl);
            boolean isCurrent = id.equals(sessionId);
            String marker = isCurrent ? GREEN + " ● " + RESET : DIM + " ○ " + RESET;
            String shortId = id.value().substring(0, Math.min(12, id.value().length()));
            int turns = (int) sl.deriveMessages().messages().stream()
                    .filter(m -> m.role() != null && "USER".equals(m.role().name())).count();
            System.out.println("  " + marker + BOLD + shortId + RESET + DIM + "  " + title + "  (" + turns + " turns)" + RESET);
        }
        System.out.println(DIM + "  Use /session <id> to switch" + RESET);
    }

    /** /session <id> — 切换到指定会话。 */
    private void handleSwitchCommand(String args) {
        if (args.isEmpty()) {
            System.out.println(YELLOW + "  Usage: /session <id>" + RESET);
            return;
        }
        if (sessions == null) {
            System.out.println(YELLOW + "  Sessions service not registered" + RESET);
            return;
        }
        String prefix = args.trim();
        SessionId found = null;
        for (SessionId id : sessions.list()) {
            if (id.value().startsWith(prefix)) { found = id; break; }
        }
        if (found == null) {
            System.out.println(YELLOW + "  Session not found: " + prefix + RESET);
            return;
        }
        sessionId = found;
        SessionLog sl = sessions.getOrCreate(found);
        String title = TurnOrchestrator.generateTitle(sl);
        System.out.println(GREEN + "  Switched to: " + title + RESET);
        System.out.println(DIM + "  Session: " + found.value() + RESET);
    }

    /** /fork — 分叉当前会话（复制全部事件到新会话）。 */
    private void handleForkCommand() {
        if (sessions == null) {
            System.out.println(YELLOW + "  Sessions service not registered" + RESET);
            return;
        }
        try {
            var sink = new CliEventSink(sessions, System.out);
            SessionLog child = orchestrator.forkSession(sessionId.value(), sink);
            sessionId = child.sessionId();
            String title = TurnOrchestrator.generateTitle(child);
            System.out.println(GREEN + "  Forked to: " + title + RESET);
            System.out.println(DIM + "  Session: " + child.sessionId().value() + RESET);
        } catch (Exception e) {
            System.out.println(YELLOW + "  Fork failed: " + e.getMessage() + RESET);
        }
    }

    /** /model — 列出或切换模型。 */
    private void handleModelCommand(String args) {
        ModelProfileStore store = context.get(ModelProfileStore.class).orElse(null);
        if (store == null) {
            System.out.println(YELLOW + "  Model store not registered" + RESET);
            return;
        }

        List<ModelProfile> profiles = store.profiles();
        if (args.isEmpty()) {
            // 列出可用模型
            String activeId = store.activeId();
            System.out.println(CYAN + "  Available models:" + RESET);
            for (ModelProfile p : profiles) {
                String marker = p.id().equals(activeId) ? GREEN + " ● " + RESET : DIM + " ○ " + RESET;
                String label = BOLD + p.displayName() + RESET + DIM + " (" + p.model() + ")" + RESET;
                System.out.println("  " + marker + label);
            }
            if (profiles.isEmpty()) {
                System.out.println(DIM + "  (no profiles configured)" + RESET);
            }
            System.out.println(DIM + "  Use /model <id> to switch" + RESET);
        } else {
            // 切换模型
            ModelProfile target = profiles.stream()
                    .filter(p -> p.id().equals(args) || p.displayName().equalsIgnoreCase(args))
                    .findFirst().orElse(null);
            if (target == null) {
                System.out.println(YELLOW + "  Model not found: " + args + RESET);
            } else {
                store.setActive(target.id());
                // 更新 ModelConfig
                context.get(ModelConfig.class).ifPresent(mc -> {
                    mc.setApiKey(target.apiKey());
                    mc.setBaseUrl(target.baseUrl());
                    mc.setModel(target.model());
                });
                System.out.println(GREEN + "  Switched to: " + target.displayName() + " (" + target.model() + ")" + RESET);
                System.out.println(DIM + "  Note: restart for the new model to take effect (agent caches model at startup)" + RESET);
            }
        }
    }

    /** /compact — 压缩上下文。 */
    private void handleCompactCommand(String args) {
        Sessions sessions = context.get(Sessions.class).orElse(null);
        if (sessions == null) {
            System.out.println(YELLOW + "  Sessions service not registered" + RESET);
            return;
        }
        SessionLog slog = sessions.getOrCreate(sessionId);
        List<ChatMessage> messages = slog.deriveMessages().messages();

        if (messages.size() <= 8) {
            System.out.println(DIM + "  Not enough messages to compact (" + messages.size() + " <= 8)" + RESET);
            return;
        }

        CompactionService comp = new BasicCompactionProvider();
        List<ChatMessage> compacted = comp.compact(messages, 2048);
        int before = messages.size();
        int after = compacted.size();
        System.out.println(CYAN + "  Compacted: " + before + " → " + after + " messages" + RESET);
        System.out.println(DIM + "  (recent messages preserved + summary of older ones)" + RESET);
    }

    /** 打印 banner。 */
    private void printBanner() {
        String model = context.get(ModelConfig.class).map(ModelConfig::model).orElse("deepseek-chat");
        String cwd = SessionCwd.get() != null ? SessionCwd.get() : System.getProperty("user.dir");
        System.out.println();
        System.out.println(CYAN + BOLD + "  DeepSeek Harness" + RESET + DIM + " (dsh)" + RESET);
        System.out.println(DIM + "  Model: " + model + RESET);
        System.out.println(DIM + "  CWD:  " + cwd + RESET);
        System.out.println(DIM + "  Type /help for commands" + RESET);
        System.out.println();
    }

    /** 打印帮助（参考 Claude Code 风格）。 */
    private void printHelp() {
        System.out.println();
        System.out.println(CYAN + BOLD + "  Commands:" + RESET);
        printCmd("/help", "?", "Show this help");
        printCmd("/model", "[id]", "List or switch AI model");
        printCmd("/sessions", "/list", "List all sessions");
        printCmd("/session", "<id>", "Switch to a session");
        printCmd("/fork", "", "Fork current session");
        printCmd("/compact", "", "Compact context (keep recent + summary)");
        printCmd("/new", "", "Start a new session");
        printCmd("/tokens", "", "Show cumulative token usage");
        printCmd("/exit", "/quit", "Exit the REPL");
        System.out.println();
    }

    private void printCmd(String cmd, String alias, String desc) {
        String aliasStr = alias.isEmpty() ? "" : DIM + " or " + alias + RESET;
        System.out.println("  " + GREEN + cmd + RESET + aliasStr + DIM + " — " + desc + RESET);
    }

    /**
     * 运行一个回合：用 {@link CliTurnObserver} 逐 step 流式输出。
     */
    void runTurnStreamed(String userMessage) throws Exception {
        String sid = sessionId.value();
        int turn = orchestrator.nextTurn(sid);
        String model = context.get(ModelConfig.class).map(ModelConfig::model).orElse("deepseek-chat");
        var sink = new CliEventSink(sessions, System.out);
        orchestrator.prepareTurn(sid, userMessage, turn, model, null, sink);
        orchestrator.runAgent(sid, userMessage, turn, model, sink);
    }

    /** 入口：装配插件树并启动交互循环。 */
    public static void main(String[] args) throws Exception {
        // 模型配置来自 dataDir/model-config.json（ModelProfileStore 启动时加载活跃档案并同步到 ModelConfig）；
        // 不从环境变量读取模型/key/端点。
        Path dataDir = Path.of(System.getenv().getOrDefault("DSH_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".dsh").toString()));

        log.info("Starting CLI interactive mode (model from {}/model-config.json)", dataDir);
        Context context = Context.root();
        PluginRunner runner = new PluginRunner();
        Agent agent = new BaseBundle(dataDir).assemble(context, runner);

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
