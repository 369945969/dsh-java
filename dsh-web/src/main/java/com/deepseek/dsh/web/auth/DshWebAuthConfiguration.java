package com.deepseek.dsh.web.auth;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.deepseek.dsh.web.auth.BrowserAuth;

/**
 * 认证装配 —— 生成签名密钥/启动令牌（对应 harness web profile 的 client-connection 挂载）。
 *
 * <p>密钥文件位于数据目录（{@code DSH_DATA_DIR}，默认 ~/.dsh）下的
 * {@code browser-session.json}；启动完成后打印带令牌的访问 URL，
 * 与 {@code dsh web} 行为一致。
 */
@Component
public class DshWebAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DshWebAuthConfiguration.class);

    private final Environment environment;
    private final BrowserAuth auth;

    public DshWebAuthConfiguration(
            Environment environment,
            @Value("${dsh.auth.cookieMaxAgeDays:30}") int cookieMaxAgeDays) throws IOException {
        this.environment = environment;
        Path dataDir = Path.of(System.getenv().getOrDefault("DSH_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".dsh").toString()));
        this.auth = BrowserAuth.create(dataDir.resolve("browser-session.json"), cookieMaxAgeDays);
    }

    /** Spring 容器暴露的认证器（过滤器使用同一实例）。 */
    @org.springframework.context.annotation.Bean
    public BrowserAuth browserAuth() {
        return auth;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printLaunchUrl() {
        int port = environment.getProperty("server.port", Integer.class, 8765);
        log.info("dsh web authentication URL: {}", auth.authenticatedUrl("localhost", port));
    }
}
