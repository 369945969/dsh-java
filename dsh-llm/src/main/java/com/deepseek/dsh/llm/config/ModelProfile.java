package com.deepseek.dsh.llm.config;

import java.util.List;
import java.util.Map;

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
 * @param model       主模型名（模型清单的首项 id；运行时 {@link ModelConfig} 即用此值）
 * @param models      模型清单（可多模型；空则退化为单 model）。设置页编辑「模型列表」时整体写入。
 * @param route       设置页路由键（llm-pi-ai.providers 的 dict 键 + 凭据引用词干）。持久化以跨重启存活。
 */
public record ModelProfile(
        String id,
        String displayName,
        String apiKey,
        String baseUrl,
        String model,
        List<Map<String, Object>> models,
        String route
) {
    public ModelProfile(String id, String displayName, String apiKey, String baseUrl, String model,
                        List<Map<String, Object>> models) {
        this(id, displayName, apiKey, baseUrl, model, models, "");
    }

    public ModelProfile(String id, String displayName, String apiKey, String baseUrl, String model) {
        this(id, displayName, apiKey, baseUrl, model, null, "");
    }

    public ModelProfile {
        if (displayName == null || displayName.isBlank()) {
            displayName = model == null ? "未命名模型" : model;
        }
        if (apiKey == null) apiKey = "";
        if (baseUrl == null) baseUrl = "";
        if (model == null) model = "";
        if (models == null) models = List.of();
        if (route == null) route = "";
    }
}
