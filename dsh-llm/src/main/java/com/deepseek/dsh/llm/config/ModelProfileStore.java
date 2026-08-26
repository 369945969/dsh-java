package com.deepseek.dsh.llm.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.deepseek.dsh.core.context.Service;

/**
 * 模型档案注册表 —— 多自定义模型的管理与持久化中心。
 *
 * <p>对应 Web 设置页「添加自定义模型」能力：用户可保存多个模型档案（不同 provider/key/模型），
 * 切换当前活跃模型。变更持久化到 {@code model-config.json}（dataDir 下，**已 gitignore**，
 * 绝不提交 API Key），重启后自动加载。
 *
 * <p>配置优先级：页面配置（活跃档案）覆盖环境变量初值。当活跃档案存在且已配置 Key 时，
 * {@link ModelConfig}（运行时持有者，{@link com.deepseek.dsh.llm.deepseek.DeepSeekLlmAdapter}
 * 每次请求动态读取）即时反映；无活跃档案时回退到启动时的环境变量初值。
 *
 * <p>设计模式：注册表 + 仓储（持久化）+ 观察者（变更同步到 ModelConfig）。
 */
public final class ModelProfileStore implements Service {

    private static final Logger log = LoggerFactory.getLogger(ModelProfileStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configFile;
    private final ModelConfig runtimeConfig;
    private final CopyOnWriteArrayList<ModelProfile> profiles = new CopyOnWriteArrayList<>();
    private volatile String activeId;

    /**
     * @param configFile     持久化文件路径（dataDir/model-config.json）
     * @param runtimeConfig  运行时配置持有者（活跃档案变更时同步）
     * @param initialEnvKey 启动时环境变量 API Key（作为默认档案的初值，无则空）
     * @param initialEnvBaseUrl 启动时环境变量端点
     * @param initialEnvModel   启动时环境变量模型名
     */
    public ModelProfileStore(Path configFile, ModelConfig runtimeConfig,
                              String initialEnvKey, String initialEnvBaseUrl, String initialEnvModel) {
        this.configFile = configFile;
        this.runtimeConfig = runtimeConfig;
        load();
        // 无任何档案时，从环境变量种入一个默认档案
        if (profiles.isEmpty() && (isPresent(initialEnvKey) || isPresent(initialEnvModel))) {
            ModelProfile def = new ModelProfile(
                    UUID.randomUUID().toString(),
                    isPresent(initialEnvModel) ? initialEnvModel : "默认模型",
                    initialEnvKey == null ? "" : initialEnvKey,
                    isPresent(initialEnvBaseUrl) ? initialEnvBaseUrl : "https://api.deepseek.com",
                    isPresent(initialEnvModel) ? initialEnvModel : "deepseek-chat");
            profiles.add(def);
            this.activeId = def.id();
            persist();
        }
        syncRuntime();
    }

    /** 全部档案。 */
    public List<ModelProfile> profiles() {
        return List.copyOf(profiles);
    }

    /** 当前活跃档案 ID。 */
    public String activeId() {
        return activeId;
    }

    /** 当前活跃档案（可能为空）。 */
    public Optional<ModelProfile> active() {
        if (activeId == null) return Optional.empty();
        return profiles.stream().filter(p -> p.id().equals(activeId)).findFirst();
    }

    /** 添加一个自定义模型档案并返回。 */
    public synchronized ModelProfile add(String displayName, String apiKey, String baseUrl, String model) {
        ModelProfile p = new ModelProfile(
                UUID.randomUUID().toString(), displayName, apiKey, baseUrl, model);
        profiles.add(p);
        // 首个档案自动设为活跃
        if (activeId == null) activeId = p.id();
        persist();
        syncRuntime();
        return p;
    }

    /** 更新一个档案（按 id）。 */
    public synchronized boolean update(String id, String displayName, String apiKey, String baseUrl, String model) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(id)) {
                profiles.set(i, new ModelProfile(id, displayName, apiKey, baseUrl, model));
                persist();
                syncRuntime();
                return true;
            }
        }
        return false;
    }

    /** 删除一个档案（按 id）。 */
    public synchronized boolean delete(String id) {
        boolean removed = profiles.removeIf(p -> p.id().equals(id));
        if (removed) {
            if (id.equals(activeId)) {
                activeId = profiles.isEmpty() ? null : profiles.get(0).id();
            }
            persist();
            syncRuntime();
        }
        return removed;
    }

    /** 设为当前活跃档案（按 id）。 */
    public synchronized boolean setActive(String id) {
        boolean exists = profiles.stream().anyMatch(p -> p.id().equals(id));
        if (!exists) return false;
        this.activeId = id;
        persist();
        syncRuntime();
        return true;
    }

    /** 把活跃档案同步到运行时 ModelConfig（DeepSeekLlmAdapter 即时读取）。 */
    private void syncRuntime() {
        ModelProfile a = active().orElse(null);
        if (a != null) {
            runtimeConfig.setApiKey(a.apiKey());
            runtimeConfig.setBaseUrl(a.baseUrl());
            runtimeConfig.setModel(a.model());
        }
        // 无活跃档案时，runtimeConfig 保留环境变量初值（由 BaseBundle 装入）
    }

    // ---- 持久化 ----

    private void load() {
        if (!Files.isReadable(configFile)) return;
        try {
            var root = MAPPER.readTree(Files.readString(configFile));
            this.activeId = root.path("activeId").asText(null);
            ArrayNode arr = (ArrayNode) root.path("profiles");
            if (arr != null) {
                for (var n : arr) {
                    profiles.add(new ModelProfile(
                            n.path("id").asText(),
                            n.path("displayName").asText(),
                            n.path("apiKey").asText(""),
                            n.path("baseUrl").asText(""),
                            n.path("model").asText("")));
                }
            }
            log.info("加载 {} 个模型档案", profiles.size());
        } catch (Exception e) {
            log.warn("加载模型档案失败: {}", e.toString());
        }
    }

    synchronized void persist() {
        try {
            Files.createDirectories(configFile.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            root.put("activeId", activeId == null ? "" : activeId);
            ArrayNode arr = root.putArray("profiles");
            for (ModelProfile p : profiles) {
                ObjectNode o = arr.addObject();
                o.put("id", p.id());
                o.put("displayName", p.displayName());
                o.put("apiKey", p.apiKey());
                o.put("baseUrl", p.baseUrl());
                o.put("model", p.model());
            }
            Files.writeString(configFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            log.warn("持久化模型档案失败: {}", e.toString());
        }
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
