package com.deepseek.dsh.capability.web.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepseek.dsh.capability.web.WebSearchProvider;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.settings.SettingsService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * DeepSeek 搜索提供者 —— 对应原 Harness 的 {@code web-search-deepseek}。
 *
 * <p>通过 DeepSeek API 原生 web_search 能力执行搜索。
 * 若未配置 API key 则退化为空结果（不阻断 agent）。
 *
 * <p>运行时从 {@code web-search-deepseek} settings 读取：
 * apiKeyEnv（凭据引用→环境变量名，缺省回退构造时传入的 fallback key）、
 * baseURL（缺省回退 fallback）。maxUses 由 WebSearchTool 读取并截断 max_results。
 *
 * <p>设计模式：策略的具体实现。
 */
public final class DeepSeekSearchProvider implements WebSearchProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient();
    private final Context ctx;
    private final String fallbackApiKey;
    private final String fallbackBaseUrl;

    public DeepSeekSearchProvider(String apiKey) {
        this(null, apiKey, "https://api.deepseek.com");
    }

    public DeepSeekSearchProvider(String apiKey, String baseUrl) {
        this(null, apiKey, baseUrl);
    }

    public DeepSeekSearchProvider(Context ctx, String apiKey, String baseUrl) {
        this.ctx = ctx;
        this.fallbackApiKey = apiKey;
        this.fallbackBaseUrl = baseUrl;
    }

    private String setting(String key) {
        if (ctx == null) return null;
        return ctx.get(SettingsService.class)
                .map(s -> s.getAll("web-search-deepseek").get(key))
                .orElse(null);
    }

    private String apiKey() {
        String envRef = setting("apiKeyEnv");
        if (envRef != null && !envRef.isBlank()) {
            String v = System.getenv(envRef);
            if (v != null && !v.isBlank()) return v;
        }
        return fallbackApiKey;
    }

    private String baseUrl() {
        String b = setting("baseURL");
        return (b != null && !b.isBlank()) ? b : fallbackBaseUrl;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        String apiKey = apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", "deepseek-chat",
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", "搜索: " + query)),
                    "web_search", true));
            Request req = new Request.Builder()
                    .url(baseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) return List.of();
                JsonNode json = mapper.readTree(resp.body().string());
                List<SearchResult> results = new ArrayList<>();
                JsonNode choices = json.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    String content = choices.get(0).path("message").path("content").asText("");
                    // 简化：将模型回复作为单条结果
                    results.add(SearchResult.of(query, baseUrl(), content.length() > 500
                            ? content.substring(0, 500) + "…" : content));
                }
                return results.size() > maxResults ? results.subList(0, maxResults) : results;
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}
