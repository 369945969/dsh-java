package com.deepseek.dsh.llm.config;

/**
 * 模型档案 —— 一个命名的可保存模型配置（对应 Web 设置页中「添加自定义模型」）。
 *
 * <p>用户可在设置页添加多个自定义模型（如 glm-5.2、deepseek-chat、qwen-plus），
 * 每个档案独立保存 API Key / 端点 / 模型名，并可切换为当前活跃模型。
 *
 * @param id          档案 ID（稳定标识，用于切换/删除）
 * @param displayName 显示名（如「阿里云 glm-5.2」）
 * @param apiKey      模型 API Key
 * @param baseUrl     OpenAI 兼容端点
 * @param model       模型名（如 glm-5.2）
 */
public record ModelProfile(
        String id,
        String displayName,
        String apiKey,
        String baseUrl,
        String model
) {
    public ModelProfile {
        if (displayName == null || displayName.isBlank()) {
            displayName = model == null ? "未命名模型" : model;
        }
        if (apiKey == null) apiKey = "";
        if (baseUrl == null) baseUrl = "";
        if (model == null) model = "";
    }
}
