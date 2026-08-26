package com.deepseek.dsh.app.acp;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.acp.AcpServer;
import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.app.bundle.BaseBundle;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.util.PluginRunner;

/**
 * ACP 服务端入口 —— 对应原 Harness 的 {@code dsh acp}（Automation-only Agent Client Protocol）。
 *
 * <p>以 newline-delimited JSON-RPC 2.0 over stdio 暴露<b>自动化专用</b>协议：
 * 会话的创建/运行/列出/关闭。与 {@link com.deepseek.dsh.app.rpc.DshRpcServer} 互补——
 * 后者面向运行时 SDK（全功能），本类面向 ACP 自动化消费方（最小方法集）。
 *
 * <p>stdout 仅承载 JSON-RPC 帧，日志走 stderr（{@code logback-rpc.xml}），
 * 保证自动化客户端可干净读取。
 *
 * <p>启动：{@code java -Dlogback.configurationFile=logback-rpc.xml
 * com.deepseek.dsh.app.acp.DshAcpServer}；环境变量
 * {@code DEEPSEEK_API_KEY} / {@code DSH_BASE_URL} / {@code DSH_MODEL} 配置模型。
 *
 * <p>设计模式：命令注册 + 前端控制器 + 依赖注入（手动装配插件树，复用 {@link BaseBundle}）。
 */
public final class DshAcpServer {

    private static final Logger log = LoggerFactory.getLogger(DshAcpServer.class);

    private final AcpServer server;

    public DshAcpServer(Context context, Agent agent) {
        this.server = new AcpServer(context, agent);
    }

    /** 在 stdio 上运行 ACP 协议循环。 */
    public void runLoop() throws java.io.IOException {
        server.runLoop(System.in, System.out);
    }

    /** 入口：装配插件树并启动 ACP 循环。 */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("DSH_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("DSH_MODEL", "deepseek-chat");
        Path dataDir = Path.of(System.getenv().getOrDefault("DSH_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".dsh").toString()));

        log.info("启动 ACP 服务端: model={}, baseUrl={}", model, baseUrl);
        Context context = Context.root();
        PluginRunner runner = new PluginRunner();
        Agent agent = new BaseBundle(apiKey, baseUrl, model, dataDir).assemble(context, runner);

        DshAcpServer acp = new DshAcpServer(context, agent);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("卸载插件树...");
            runner.stop();
            context.dispose();
        }));
        acp.runLoop();
        log.info("ACP 循环结束，退出");
        runner.stop();
        context.dispose();
    }
}
