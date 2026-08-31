package com.deepseek.dsh.subagent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.agent.react.ReActAgentLoop;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.config.ModelConfig;
import com.deepseek.dsh.llm.config.ModelProfileStore;
import com.deepseek.dsh.llm.deepseek.DeepSeekLlmAdapter;
import com.deepseek.dsh.llm.retry.RetryLlmModel;
import com.deepseek.dsh.settings.SettingsService;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentService;
import com.deepseek.dsh.tools.pipeline.ToolPipeline;
import com.deepseek.dsh.tools.registry.Tool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.ToolRegistry;
import com.deepseek.dsh.tools.schema.ToolSchema;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * task 委派工具 —— 对应原 Harness 的 {@code tool-subagent}。
 *
 * <p>让主 agent 将一个子任务委派给子 agent 执行。子 agent 完成后返回摘要报告。
 *
 * <p>当 {@code subagent-model-selection.enabled} 为真且 {@code allowedModels} 非空时，
 * schema 暴露 {@code model} 枚举（allowedModels）；主 agent 选定模型后，按该模型构造
 * per-delegation 子 agent（独立 LLM adapter + ModelConfig，复用同一 pipeline/toolRegistry）。
 * 否则用默认子 agent（active profile 模型）。
 *
 * <p>设计模式：命令（Command）+ 代理（委派给子 agent）。
 */
public final class SubagentTaskTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Agent subagent;
    private final Context ctx;

    public SubagentTaskTool(Agent subagent, Context ctx) {
        this.subagent = subagent;
        this.ctx = ctx;
    }

    @Override
    public ToolSchema schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", Map.of("type", "string", "description", "子任务的详细描述（目标、约束）"));
        props.put("prompt", Map.of("type", "string", "description", "传给子 agent 的具体提示（可选）"));
        List<String> allowed = selectionEnabled() ? allowedModels() : List.of();
        if (!allowed.isEmpty()) {
            props.put("model", Map.of("type", "string", "enum", allowed,
                    "description", "子 agent 使用的模型，从允许的子路由中选择"));
        }
        return ToolSchema.of("task", "将子任务委派给一个子 agent 执行并返回摘要报告。", Map.of(
                "type", "object", "properties", props, "required", List.of("description")));
    }

    @Override
    public String invoke(Map<String, Object> arguments, ToolContext tctx) throws Exception {
        Context context = tctx.context();
        SubagentService subagents = context.get(SubagentService.class).orElse(null);
        if (subagents == null) {
            return "（subagent 服务未注册）";
        }
        String task = arguments.get("description") instanceof String s ? s : "";
        String prompt = arguments.get("prompt") instanceof String p ? p : null;
        String fullTask = prompt != null ? prompt : task;
        String model = arguments.get("model") instanceof String m ? m : null;

        Agent delegate = subagent;
        List<String> allowed = selectionEnabled() ? allowedModels() : List.of();
        if (model != null && !model.isBlank() && !allowed.isEmpty() && allowed.contains(model)) {
            Agent perModel = buildSubagentFor(model, context);
            if (perModel != null) delegate = perModel;
        }

        DelegationResult result = subagents.delegate(
                tctx.sessionId(), tctx.scopeKey(), context, delegate, fullTask);
        return result.success()
                ? "子任务完成:\n" + result.report()
                : "子任务失败:\n" + result.report();
    }

    /** enabled 时暴露 model 枚举，按所选模型构造 per-delegation 子 agent。 */
    private boolean selectionEnabled() {
        String v = setting("enabled");
        return v != null && "true".equalsIgnoreCase(v);
    }

    private List<String> allowedModels() {
        String v = setting("allowedModels");
        if (v == null || v.isBlank()) return List.of();
        try {
            var n = MAPPER.readTree(v);
            if (!n.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (var e : n) {
                String id = e.path("id").asText("");
                if (!id.isEmpty()) out.add(id);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String setting(String key) {
        return ctx.get(SettingsService.class)
                .map(s -> s.getAll("subagent-model-selection").get(key))
                .orElse(null);
    }

    /** 用指定模型构造 per-delegation 子 agent：独立 adapter/ModelConfig，复用 pipeline/toolRegistry。 */
    private Agent buildSubagentFor(String model, Context context) {
        var store = context.get(ModelProfileStore.class).orElse(null);
        var ap = store == null ? null : store.active().orElse(null);
        String apiKey = ap != null && ap.apiKey() != null ? ap.apiKey() : "";
        String baseUrl = ap != null && ap.baseUrl() != null && !ap.baseUrl().isBlank()
                ? ap.baseUrl() : "https://api.deepseek.com";
        var adapter = new DeepSeekLlmAdapter(apiKey, baseUrl, model);
        adapter.setConfig(new ModelConfig(apiKey, baseUrl, model));
        LlmModel subLlm = new RetryLlmModel(adapter);
        var pipeline = context.get(ToolPipeline.class).orElse(null);
        var reg = context.get(ToolRegistry.class).orElse(null);
        if (pipeline == null || reg == null) return null;
        var sub = new ReActAgentLoop(subLlm, pipeline, reg);
        sub.setSystemPrompt("You are a delegated sub-agent. Complete the assigned task concisely and report the result.");
        return sub;
    }
}
