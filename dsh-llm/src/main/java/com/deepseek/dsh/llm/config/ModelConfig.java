package com.deepseek.dsh.llm.config;

import com.deepseek.dsh.core.context.Service;

/**
 * 模型配置持有者 —— 运行时可变的模型配置（API Key / 端点 / 模型名）。
 *
 * <p>支持两种配置方式并存：
 * <ol>
 *   <li><b>环境变量 / .env</b>（export）：启动时由 {@code BaseBundle} 从
 *       {@code DEEPSEEK_API_KEY} / {@code DSH_BASE_URL} / {@code DSH_MODEL} 装入初值。</li>
 *   <li><b>页面配置</b>：运行时通过 Web 设置页（{@code /api/config/model}）更新，
 *       持久化到 {@code model-config.json}，重启后自动加载。</li>
 * </ol>
 * 页面配置覆盖环境变量初值；两者任一有效即可驱动模型调用。
 *
 * <p>{@link com.deepseek.dsh.llm.deepseek.DeepSeekLlmAdapter} 每次请求<b>动态读取</b>
 * 本持有者的当前值，故页面更新即时生效（下一回合即用新配置）。
 *
 * <p>设计模式：值对象（可变）+ 共享状态（Context 注册为服务）。
 */
public final class ModelConfig implements Service {

    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile String model;

    public ModelConfig(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.model = model == null ? "" : model;
    }

    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }

    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl; }
    public void setModel(String model) { this.model = model == null ? "" : model; }

    /** 是否已配置有效 API Key。 */
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    /**
     * 返回脱敏的 API Key（仅保留首尾若干字符），供 API 响应展示，避免泄露完整密钥。
     */
    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) return "";
        if (apiKey.length() <= 8) return "*".repeat(apiKey.length());
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
