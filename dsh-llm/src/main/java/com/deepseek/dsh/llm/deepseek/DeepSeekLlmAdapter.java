package com.deepseek.dsh.llm.deepseek;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.llm.adapter.LlmChunk;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.adapter.LlmRequest;
import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.llm.config.ModelConfig;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.tools.schema.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * DeepSeek 模型适配器 —— 调用 DeepSeek Chat Completions API。
 *
 * <p>对应原 Harness 的 {@code dsh-llm-deepseek}。支持非流式 chat 与流式 stream，
 * 支持 function-calling 工具。
 *
 * <p>设计模式：适配器（将 DeepSeek HTTP API 适配为 {@link LlmModel} 统一接口）。
 */
public final class DeepSeekLlmAdapter implements LlmModel {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmAdapter.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    /** 运行时可变配置（页面配置）；非 null 时覆盖构造值，每次请求动态读取。 */
    private volatile ModelConfig config;
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekLlmAdapter(String apiKey, String model) {
        this(apiKey, "https://api.deepseek.com", model);
    }

    public DeepSeekLlmAdapter(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    /** 注入运行时可变配置（页面配置覆盖环境变量初值）。 */
    public void setConfig(ModelConfig config) {
        this.config = config;
    }

    /** 当前生效的 API Key：页面配置优先，回退构造值。 */
    private String effectiveApiKey() {
        ModelConfig c = config;
        return c != null && c.isConfigured() ? c.apiKey() : apiKey;
    }

    /** 当前生效的端点：页面配置优先，回退构造值。 */
    private String effectiveBaseUrl() {
        ModelConfig c = config;
        return c != null && !c.baseUrl().isBlank() ? c.baseUrl() : baseUrl;
    }

    /** 当前生效的模型名：页面配置优先，回退构造值。 */
    private String effectiveModel() {
        ModelConfig c = config;
        return c != null && !c.model().isBlank() ? c.model() : model;
    }

    @Override
    public LlmResponse chat(LlmRequest request) throws Exception {
        ObjectNode body = buildRequestBody(request, false);
        Request httpReq = new Request.Builder()
                .url(effectiveBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + effectiveApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();

        try (Response resp = client.newCall(httpReq).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() != null ? resp.body().string() : "";
                throw new com.deepseek.dsh.core.exception.LlmException(
                        effectiveModel(), resp.code(),
                        "DeepSeek API 错误 " + resp.code() + ": " + errBody, null);
            }
            if (resp.body() == null) {
                throw new com.deepseek.dsh.core.exception.LlmException(
                        effectiveModel(), 0, "响应体为空", null);
            }
            JsonNode json = mapper.readTree(resp.body().string());
            return parseResponse(json);
        }
    }

    @Override
    public Flow.Publisher<LlmChunk> stream(LlmRequest request) throws Exception {
        ObjectNode body = buildRequestBody(request, true);
        Request httpReq = new Request.Builder()
                .url(effectiveBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + effectiveApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();

        SubmissionPublisher<LlmChunk> pub = new SubmissionPublisher<>();
        EventSource es = EventSources.createFactory(client)
                .newEventSource(httpReq, new EventSourceListener() {
                    @Override
                    public void onEvent(EventSource source, String id, String type, String data) {
                        if ("[DONE]".equals(data)) {
                            pub.submit(LlmChunk.done());
                            return;
                        }
                        try {
                            JsonNode json = mapper.readTree(data);
                            JsonNode delta = json.path("choices").path(0).path("delta");
                            String content = delta.path("content").asText("");
                            if (!content.isEmpty()) {
                                pub.submit(LlmChunk.delta(content));
                            }
                        } catch (Exception e) {
                            log.warn("解析流式分块失败: {}", e.toString());
                        }
                    }

                    @Override
                    public void onClosed(EventSource source) {
                        pub.close();
                    }
                });
        // 持有引用避免被 GC
        pub.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) { this.sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(LlmChunk item) {}
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() { es.cancel(); }
        });
        return pub;
    }

    private ObjectNode buildRequestBody(LlmRequest request, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", effectiveModel());
        body.put("stream", stream);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());

        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("role", m.role().name().toLowerCase());
            if (m.content() != null && !m.content().isEmpty()) {
                msg.put("content", m.content());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode tcs = msg.putArray("tool_calls");
                for (ChatMessage.ToolCall tc : m.toolCalls()) {
                    ObjectNode tcNode = tcs.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.argumentsJson());
                }
            }
            if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
            }
        }

        // 工具
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSchema ts : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", ts.name());
                fn.put("description", ts.description());
                fn.set("parameters", mapper.valueToTree(ts.parameters()));
            }
        }
        return body;
    }

    private LlmResponse parseResponse(JsonNode json) {
        JsonNode choice = json.path("choices").path(0).path("message");
        String content = choice.path("content").asText("");
        String finish = json.path("choices").path(0).path("finish_reason").asText("stop");

        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcs = choice.path("tool_calls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                toolCalls.add(new ChatMessage.ToolCall(
                        tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText("{}")
                ));
            }
        }

        JsonNode usage = json.path("usage");
        LlmResponse.TokenUsage u = new LlmResponse.TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0));

        return new LlmResponse(content, toolCalls, u, finish);
    }
}
