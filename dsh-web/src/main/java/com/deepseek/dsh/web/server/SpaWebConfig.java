package com.deepseek.dsh.web.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 静态资源配置 —— 将非 API 路径回退到前端 index.html（History 路由）。
 *
 * <p>对应原 Harness 的 {@code frontend-static}：由后端服务托管构建好的前端产物。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 前端首页
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
