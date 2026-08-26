package com.deepseek.dsh.capability.web.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepseek.dsh.capability.web.WebSearchProvider;
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
 * <p>设计模式：策略的具体实现。
 */
public final class DeepSeekSearchProvider implements WebSearchProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient();
    private final String apiKey;
    private final String baseUrl;

    public DeepSeekSearchProvider(String apiKey) {
        this(apiKey, "https://api.deepseek.com");
    }

    public DeepSeekSearchProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
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
                    .url(baseUrl + "/chat/completions")
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
                    results.add(SearchResult.of(query, baseUrl, content.length() > 500
                            ? content.substring(0, 500) + "…" : content));
                }
                return results.size() > maxResults ? results.subList(0, maxResults) : results;
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}
