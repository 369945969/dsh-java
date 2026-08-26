package com.deepseek.dsh.capability.web.fetch;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * HTTP 抓取提供者 —— 对应原 Harness 的 {@code web-fetch-http}。
 *
 * <p>用 OkHttp 发起匿名 GET 请求，返回正文文本（带长度截断）。
 *
 * <p>设计模式：策略的具体实现。
 */
public final class HttpFetchProvider implements com.deepseek.dsh.capability.web.WebFetchProvider {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .build();

    @Override
    public String fetch(String url, int maxChars) {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "DeepSeek-Harness/0.1")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                return "HTTP " + resp.code() + ": " + resp.message();
            }
            String body = resp.body() != null ? resp.body().string() : "";
            if (maxChars > 0 && body.length() > maxChars) {
                body = body.substring(0, maxChars) + "\n…[已截断]";
            }
            return body;
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }
}
