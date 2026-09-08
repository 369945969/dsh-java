package com.deepseek.dsh.core.context;

/**
 * 会话级应用/用户标识（appid/userid）+ 推理标记（reasoning）+ 模型 id（modelId）+ 工作区 id（workspaceId）
 * —— 由 Web/WS 层在 agent 回合开始前从 HTTP header（X-DSH-APPID / X-DSH-USERID /
 * X-DSH-REASONING / X-DSH-MODEL / X-DSH-WORKSPACE）设置，RPC 层从环境变量设置。
 * 未设置时取默认值：appid="default"，userid=""（匿名），reasoning="auto"，modelId=""，workspaceId=""。
 *
 * <p>供 SessionManager 在创建会话时盖章到 SessionLog，进而落 DB（appid/userid/reasoning/model_id/workspace_id 列）。
 * reasoning 供 DeepSeekLlmAdapter 决定是否设 enable_thinking（auto=不设，走模型默认）。
 * modelId 供 DeepSeekLlmAdapter 覆盖请求模型（空=用活跃档案模型）。
 *
 * <p>使用 ThreadLocal：agent 回合在同一线程（虚拟线程）内执行，与 {@link SessionCwd} 同款隐式上下文传递。
 *
 * <p>设计模式：线程局部单例（隐式上下文传递）。
 */
public final class SessionAuth {
    private static final String DEFAULT_APPID = "default";
    private static final String DEFAULT_USERID = "";
    /** 推理标记默认值：auto = 不干预，按模型默认行为。 */
    private static final String DEFAULT_REASONING = "auto";
    /** 模型 id 默认值：空 = 不覆盖，用活跃档案模型。 */
    private static final String DEFAULT_MODEL_ID = "";
    /** 工作区 id 默认值：空 = 不属于任何工作区。 */
    private static final String DEFAULT_WORKSPACE_ID = "";

    private static final ThreadLocal<String> APPID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERID = new ThreadLocal<>();
    private static final ThreadLocal<String> REASONING = new ThreadLocal<>();
    private static final ThreadLocal<String> MODEL_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> WORKSPACE_ID = new ThreadLocal<>();

    /** 设置当前线程的 appid/userid/reasoning/modelId/workspaceId；空值归一为默认。 */
    public static void set(String appid, String userid, String reasoning, String modelId, String workspaceId) {
        APPID.set((appid == null || appid.isBlank()) ? DEFAULT_APPID : appid);
        USERID.set(userid == null ? DEFAULT_USERID : userid);
        REASONING.set(normalizeReasoning(reasoning));
        MODEL_ID.set(modelId == null ? DEFAULT_MODEL_ID : modelId.trim());
        WORKSPACE_ID.set(workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId.trim());
    }

    /** 当前 appid（未设置或空→"default"）。 */
    public static String appid() {
        String v = APPID.get();
        return (v == null || v.isBlank()) ? DEFAULT_APPID : v;
    }

    /** 当前 userid（未设置→""匿名）。 */
    public static String userid() {
        String v = USERID.get();
        return v == null ? DEFAULT_USERID : v;
    }

    /** 当前推理标记：true=强制开，false=强制关，auto=不干预（模型默认）。 */
    public static String reasoning() {
        String v = REASONING.get();
        return (v == null || v.isBlank()) ? DEFAULT_REASONING : v;
    }

    /** 当前模型 id（未设置→""，表示用活跃档案模型，不覆盖）。 */
    public static String modelId() {
        String v = MODEL_ID.get();
        return v == null ? DEFAULT_MODEL_ID : v;
    }

    /** 当前工作区 id（未设置→""，表示不属于任何工作区）。 */
    public static String workspaceId() {
        String v = WORKSPACE_ID.get();
        return v == null ? DEFAULT_WORKSPACE_ID : v;
    }

    /** 清除当前线程上下文（回合结束调用，防虚拟线程复用串号）。 */
    public static void clear() {
        APPID.remove();
        USERID.remove();
        REASONING.remove();
        MODEL_ID.remove();
        WORKSPACE_ID.remove();
    }

    /** 归一 reasoning：仅接受 true/false/auto，其余归一为 auto。 */
    private static String normalizeReasoning(String r) {
        if (r == null || r.isBlank()) return DEFAULT_REASONING;
        String v = r.trim().toLowerCase();
        if ("true".equals(v) || "false".equals(v) || "auto".equals(v)) return v;
        return DEFAULT_REASONING;
    }

    private SessionAuth() {}
}
