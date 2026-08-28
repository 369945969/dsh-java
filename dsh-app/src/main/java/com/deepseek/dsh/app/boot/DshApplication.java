package com.deepseek.dsh.app.boot;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.app.bundle.BaseBundle;
import com.deepseek.dsh.app.profile.Profile;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.util.PluginRunner;
import com.deepseek.dsh.web.server.AgentContextHolder;

import jakarta.annotation.PreDestroy;

/**
 * Spring Boot 启动入口 —— 对应原 Harness 的 {@code apps/cli}（{@code dsh web} 模式）。
 *
 * <p>在启动时装配 profile（组合插件树），构建 agent，并暴露给 Web 层。
 *
 * <p>设计模式：前端控制器 + 依赖注入容器（Spring）。
 */
@SpringBootApplication(scanBasePackages = "com.deepseek.dsh.web")
public class DshApplication implements AgentContextHolder {

    private static final Logger log = LoggerFactory.getLogger(DshApplication.class);

    private Context context;
    private PluginRunner runner;
    private Agent agent;
    /** 装配完成闩：ApplicationReadyEvent 在 Web 已收连接后才触发，装配与首批请求存在竞态——装配完成前到达的请求阻塞等待，而非 NPE/「未注册服务」。 */
    private final java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(1);

    public static void main(String[] args) {
        SpringApplication.run(DshApplication.class, args);
    }

    /**
     * 在 Spring 容器初始化后装配 agent。
     * 通过监听 ApplicationReadyEvent 确保插件树在 Web 就绪前挂载完成。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() throws Exception {
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("DSH_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("DSH_MODEL", "deepseek-chat");
        Path dataDir = Path.of(System.getenv().getOrDefault("DSH_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".dsh").toString()));

        try {
            log.info("Assembling profile: model={}, baseUrl={}, dataDir={}", model, baseUrl, dataDir);
            this.context = Context.root();
            this.runner = new PluginRunner();
            Profile profile = Profile.defaultWeb(apiKey, baseUrl, model, dataDir);
            this.agent = profile.assemble(context, runner);
            log.info("Agent assembled: {}", agent.name());
        } finally {
            // 无论装配成功与否都放行等待中的请求；装配失败时 context/agent 仍为 null，由调用方处理
            readyLatch.countDown();
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Unloading plugin tree...");
        if (runner != null) runner.stop();
        if (context != null) context.dispose();
    }

    @Override
    public Context context() {
        awaitReady();
        return context;
    }

    @Override
    public Agent agent() {
        awaitReady();
        return agent;
    }

    private void awaitReady() {
        try {
            readyLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
