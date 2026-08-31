package com.deepseek.dsh.web.api;

import com.deepseek.dsh.web.TurnOrchestrator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.llm.config.ModelProfile;
import com.deepseek.dsh.llm.config.ModelProfileStore;
import com.deepseek.dsh.settings.SettingsService;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.web.server.AgentContextHolder;
import com.deepseek.dsh.web.server.ApiproxyDownlinkRegistry;
import com.deepseek.dsh.web.server.RemoteMuxRegistry;
import com.deepseek.dsh.web.server.WorkspaceRegistry;

/**
 * apiproxy JSON-RPC 网关（对接原版 Cordis 前端）—— {@code POST /api/{method}}。
 *
 * <p>实现原 Harness apiproxy 的最小可用子集：
 * <ul>
 *   <li>{@code host.describe}：连接握手必需。</li>
 *   <li>{@code session.create}：建会话，推 {@code session/subscribed}；{@code host/session-added} 由
 *       {@code SessionCreatedBroadcaster} 订阅会话创建事件统一广播。</li>
 *   <li>{@code session.list}：列出 dsh-java 活跃会话为 SessionSummary。</li>
 *   <li>{@code session.prompt}：运行 agent，把 SSE 文本增量映射为 {@code assistant/chunk} 等 mux 帧推送，返回 {@code {accepted:true}}（回合异步）。</li>
 *   <li>其余启动期只读方法：返回 schema 合法的空值。</li>
 * </ul>
 *
 * <p>事件类型/数据形状对齐原 runtime 的 ConversationNode 匹配器：
 * {@code turn/start}→{turn}、{@code step/start}→{turn,step}、{@code user/message}→{id,content,source}、
 * {@code assistant/chunk}→{chunk:{type:'text-delta',index,text},turn,step}、{@code assistant/message}→{message:{id,content},turn,step}、
 * {@code step/end}、{@code turn/end}。
 */
@RestController
@RequestMapping("/api")
public class ApiproxyController {

    private static final Logger log = LoggerFactory.getLogger(ApiproxyController.class);

    private final AgentContextHolder holder;
    private final ApiproxyDownlinkRegistry downlink;
    private final WorkspaceRegistry workspaces;
    private final RemoteMuxRegistry remoteMux;
    private final AtomicLong seq = new AtomicLong(0);
    /** 路由 → 模型档案 ID。每个档案的 route 持久化于 ModelProfile.route；settings.mutate 显式建立，ensureRoutes 在启动时重建此映射。 */
    private final ConcurrentMap<String, String> routeToProfileId = new ConcurrentHashMap<>();
    private volatile boolean routesSeeded = false;

    /** 用户手动重命名的会话标题（覆盖自动生成的）。 */
    private final ConcurrentMap<String, String> customTitles = new ConcurrentHashMap<>();

    /** 当前默认 agent 预设（select 后更新，使 agentPreset.list 的 isDefault 反映用户选择）。 */
    private volatile String defaultPreset = "standard";

    /** 正在运行的 agent turn 线程（按 sessionId 索引），用于 session.cancel 中断。 */
    private final ConcurrentMap<String, Thread> runningTurns = new ConcurrentHashMap<>();

    /** session.cancel 标记的取消会话（不依赖 interrupt 是否生效，直接设标记）。 */
    private final java.util.Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();
    /** 每会话选定的模型（session.selectModel 写入）；runTurn 读取，缺省回退 active profile 的 model。 */
    private final ConcurrentMap<String, String> sessionModelSelection = new ConcurrentHashMap<>();

    /** 系统 agent 预设名单（id/显示名/说明）。用户预设存于 ~/.dsh/presets/*.yml。 */
    private static final String[][] SYSTEM_PRESETS = {
            {"standard", "标准模式", "功能完整的编码 Agent，支持文件编辑、Shell、文件与网页检索、Skills、计划、目标、子代理和工作流。"},
            {"ptc", "PTC 模式", "具备标准模式的全部能力，并通过 PTC 模式 SDK 呈现工具，让模型用一个 TypeScript 程序组合多步操作。"},
            {"minimal", "极简模式", "仅提供持久 bash 与 str_replace_editor 的双工具编码 Agent。"},
            {"cordis", "创造模式", "用于创建自定义 Agent preset：具备标准模式的全部能力，并提供运行时检查、插件实验和 preset 创作指导。"},
    };

    public ApiproxyController(AgentContextHolder holder, ApiproxyDownlinkRegistry downlink, WorkspaceRegistry workspaces, RemoteMuxRegistry remoteMux) {
        this.holder = holder;
        this.downlink = downlink;
        this.workspaces = workspaces;
        this.remoteMux = remoteMux;
        remoteMux.setSnapshotProvider(this::buildFollowSnapshot);
        remoteMux.setControlBaselineProvider(this::buildControlBaseline);
    }

    private Map<String, Object> buildControlBaseline() {
        String model = currentModelName();
        Map<String, Object> selection = Map.of("provider", "openai-compatible", "model", model);
        Map<String, Object> modelSelection = Map.of("lastUsed", selection, "next", selection);
        Map<String, Object> projections = new java.util.LinkedHashMap<>();
        try {
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
            for (SessionId id : sessions.list()) {
                SessionLog sl = sessions.getOrCreate(id);
                String title = customTitles.getOrDefault(id.value(), generateTitle(sl));
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("title", title);
                values.put("modelSelection", modelSelection);
                values.put("agentPreset", defaultPreset);
                projections.put(id.value(), Map.of("asOfSeq", sl.lastSeq(), "values", values));
            }
        } catch (Exception e) {
            log.debug("buildControlBaseline: {}", e.toString());
        }
        return Map.of("queues", Map.of(), "jobs", Map.of(), "projections", projections);
    }

    private RemoteMuxRegistry.FollowSnapshot buildFollowSnapshot(String sessionId) {
        try {
            Map<String, Object> histResult = sessionHistory(Map.of("sessionId", sessionId));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) histResult.get("events");
            long maxSeq = -1;
            for (var entry : events) {
                if (entry.get("event") instanceof Map<?, ?> e && e.get("seq") instanceof Number n) {
                    if (n.longValue() > maxSeq) maxSeq = n.longValue();
                }
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (var entry : events) {
                if (entry.get("event") instanceof Map<?, ?> e) {
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("type", "event");
                    rec.put("event", e);
                    records.add(rec);
                }
            }
            // title 与 buildControlBaseline/sessionFork 同款：用 generateTitle 跳过
            // source.kind=plugin 的上下文/技能注入消息，取首条真实用户输入。
            // 否则 live follow 快照会以高 seq 覆盖分叉响应下发的正确 title，
            // 把 "Current runtime context…" 这类注入消息当成会话名（刷新才纠正）。
            String title = "新会话";
            try {
                SessionLog sl = holder.context().require(Sessions.class).getOrCreate(SessionId.of(sessionId));
                title = TurnOrchestrator.generateTitle(sl);
            } catch (Exception ex) {
                log.debug("buildFollowSnapshot title: {}", ex.toString());
            }
            String model = currentModelName();
            Map<String, Object> selection = Map.of("provider", "openai-compatible", "model", model);
            Map<String, Object> modelSelection = Map.of("lastUsed", selection, "next", selection);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("title", title);
            values.put("modelSelection", modelSelection);
            values.put("agentPreset", defaultPreset);
            return new RemoteMuxRegistry.FollowSnapshot(
                    records, maxSeq, false,
                    Map.of("asOfSeq", maxSeq, "values", values),
                    Map.of("version", 0, "id", sessionId, "createdAt", System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("buildFollowSnapshot: {}", e.toString());
            return null;
        }
    }

    @PostMapping("/{method:^(?!events\\.|remote\\.).[A-Za-z0-9.]+$}")
    public Map<String, Object> dispatch(@PathVariable String method, @RequestBody Map<String, Object> request) {        String rpcId = echoRpcId(request);
        unwrapArgs(request);
        Object payload = request.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        log.debug("apiproxy {} payload={}", method, payload);
        try {
            return switch (method) {
                case "host.describe" -> response(rpcId, ok(hostDescribe()));
                case "session.create" -> response(rpcId, ok(sessionCreate(payload)));
                case "session.list" -> response(rpcId, ok(sessionList()));
                case "session.history" -> response(rpcId, ok(sessionHistory(payload)));
                case "session.prompt" -> response(rpcId, ok(sessionPrompt(payload)));
                case "session.cancel" -> response(rpcId, ok(sessionCancel(payload)));
                case "session.fork" -> response(rpcId, ok(sessionFork(payload)));
                case "session.rename" -> response(rpcId, ok(sessionRename(payload)));
                case "session.models", "session.modelCatalog" -> response(rpcId, ok(modelCatalog()));
                case "session.selectModel" -> response(rpcId, ok(sessionSelectModel(payload)));
                case "settings.describe" -> response(rpcId, ok(settingsDescribe()));
                case "settings.openDocument" -> response(rpcId, ok(openSettingsDocument()));
                case "settings.mutate", "settings.update", "settings.replace" -> response(rpcId, ok(settingsWrite(payload)));
                case "llm.providers" -> response(rpcId, ok(llmProviders()));
                case "llm.configurableProviders" -> response(rpcId, ok(llmConfigurableProviders()));
                case "llm.models" -> response(rpcId, ok(sessionModels()));
                case "llm.discoverModels" -> response(rpcId, ok(Map.of("models", List.of())));
                case "credentials.describe" -> response(rpcId, ok(credentialDescribe(payload)));
                case "credentials.set" -> response(rpcId, ok(credentialSet(payload)));
                case "credentials.unset" -> response(rpcId, ok(credentialUnset(payload)));
                case "agentPreset.list" -> response(rpcId, ok(agentPresetList()));
                case "agentPreset.read" -> response(rpcId, ok(agentPresetRead(payload)));
                case "agentPreset.select" -> response(rpcId, ok(agentPresetSelect(payload)));
                case "agentPreset.copy" -> response(rpcId, ok(agentPresetCopy(payload)));
                case "agentPreset.openDocument" -> response(rpcId, ok(agentPresetOpenDocument()));
                case "agentPreset.remove" -> response(rpcId, ok(agentPresetRemove(payload)));
                case "workspace.create" -> response(rpcId, ok(workspaceCreate(payload)));
                case "workspace.rename" -> response(rpcId, ok(workspaceRename(payload)));
                case "workspace.delete" -> response(rpcId, ok(workspaceDelete(payload)));
                case "workspace.insertBefore" -> response(rpcId, ok(workspaceInsertBefore()));
                case "workspace.insertSessionBefore" -> response(rpcId, ok(workspaceInsertSessionBefore(payload)));
                case "workspace.archiveSession" -> response(rpcId, ok(workspaceArchiveSession(payload)));
                case "workspace.list" -> response(rpcId, ok(Map.of("items", workspaces.list(), "archivedSessionIds", workspaces.archivedSessionIds())));
                case "host.listDirectory" -> response(rpcId, ok(listDirectory(payload)));
                case "skill.list" -> response(rpcId, ok(skillList()));
                case "messageFeedback.list", "messageFeedback.put", "messageFeedback.delete" ->
                        handleMessageFeedback(method.substring("messageFeedback.".length()), p, rpcId);
                default -> response(rpcId, ok(valueOf(method)));
            };
        } catch (RuntimeException e) {
            log.warn("apiproxy {} failed: {}", method, e.toString());
            return response(rpcId, err("internal", e.getMessage()));
        }
    }

    /**
     * 0.1.2 Remote 风格路由 {@code POST /api/<channel>/<endpoint>}：
     * harness 把 apiproxy 的一元方法迁到各域 Remote（channel = namespace，
     * endpoint = method），路径从点分改为两段。内部仍走 dispatch switch。
     */
    @PostMapping("/{channel:^(?!remote$)[A-Za-z0-9._-]+}/{endpoint}")
    public Map<String, Object> dispatchRemote(
            @PathVariable String channel, @PathVariable String endpoint,
            @RequestBody Map<String, Object> request) {
        String method = channel + "." + endpoint;
        if ("session.page".equals(method)) return response(echoRpcId(request), ok(sessionPage(request.get("payload"))));
        if ("directoryPicker.pick".equals(method)) method = "host.pickDirectory";
        if ("directoryPicker.list".equals(method)) method = "host.listDirectory";
        if ("directoryPicker.createDirectory".equals(method)) method = "host.createDirectory";
        if ("llm.listProviders".equals(method)) method = "llm.providers";
        if ("llm.listConfigurableProviders".equals(method)) method = "llm.configurableProviders";
        if ("llm.listModels".equals(method)) method = "llm.models";
        if ("agentPresets.list".equals(method)) method = "agentPreset.list";
        if ("agentPresets.read".equals(method)) method = "agentPreset.read";
        if ("agentPresets.copy".equals(method)) method = "agentPreset.copy";
        if ("agentPresets.select".equals(method)) method = "agentPreset.select";
        if ("agentPresets.deletePreset".equals(method)) method = "agentPreset.remove";
        if ("agentPresets.openDocument".equals(method)) method = "agentPreset.openDocument";
        if ("skills.list".equals(method)) method = "skill.list";
        unwrapArgs(request);
        return dispatch(method, request);
    }

    @SuppressWarnings("unchecked")
    private static void unwrapArgs(Map<String, Object> request) {
        Object payload = request.get("payload");
        if (payload instanceof Map<?, ?> pm && pm.get("args") instanceof Map<?, ?> args) {
            Object reqField = args.get("request");
            if (reqField instanceof Map<?, ?> req) {
                request.put("payload", new LinkedHashMap<>((Map<String, Object>) req));
            } else {
                request.put("payload", new LinkedHashMap<>((Map<String, Object>) args));
            }
        }
    }

    @PostMapping("/$events/result")
    public Map<String, Object> eventsResult(@RequestBody Map<String, Object> request) {
        return response(echoRpcId(request), ok(Map.of()));
    }

    /** Session log export: GET /api/session.export?sessionId=… → ZIP download (session .jsonl). */
    @GetMapping("/session.export")
    public ResponseEntity<byte[]> sessionExport(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return ResponseEntity.badRequest().build();
        java.nio.file.Path jsonl = java.nio.file.Path.of(
                System.getProperty("user.home"), ".dsh", "sessions", sessionId + ".jsonl");
        if (!java.nio.file.Files.isReadable(jsonl)) return ResponseEntity.notFound().build();
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (var zos = new java.util.zip.ZipOutputStream(baos)) {
                zos.putNextEntry(new java.util.zip.ZipEntry(sessionId + ".jsonl"));
                java.nio.file.Files.copy(jsonl, zos);
                zos.closeEntry();
            }
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + sessionId + ".zip\"")
                    .header("Content-Type", "application/zip")
                    .body(baos.toByteArray());
        } catch (Exception e) {
            log.warn("session export failed: {}", e.toString());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** dynamicCordisRunner/* 与 commands/*、pluginInventory/*：Java 后端无 Cordis Loader/动态包/命令目录，按方法返回空但 schema 合法的值以清控制台报错。 */
    @PostMapping("/dynamicCordisRunner/{method}")
    public Map<String, Object> cordisRunner(@PathVariable String method, @RequestBody Map<String, Object> request) {
        return response(echoRpcId(request), ok(switch (method) {
            case "inventory" -> List.of();
            case "syncInspectManifest" -> null;
            default -> Map.of();
        }));
    }

    @PostMapping("/commands/{method}")
    public Map<String, Object> commands(@PathVariable String method, @RequestBody Map<String, Object> request) {
        return response(echoRpcId(request), ok("list".equals(method) ? List.of() : Map.of()));
    }

    @PostMapping("/pluginInventory/{method}")
    public Map<String, Object> pluginInventory(@PathVariable String method, @RequestBody Map<String, Object> request) {
        return response(echoRpcId(request), ok("list".equals(method) ? pluginInventorySnapshot() : Map.of()));
    }

    /** messageFeedback/{list|put|delete}（REST 斜杠形式，兼容非 Cordis 客户端）。Cordis 前端走 dispatch 的点号形式（messageFeedback.put）。 */
    @PostMapping("/messageFeedback/{method}")
    public Map<String, Object> messageFeedback(@PathVariable String method, @RequestBody Map<String, Object> request) {
        String rpcId = echoRpcId(request);
        unwrapArgs(request);
        Object payload = request.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        log.debug("apiproxy messageFeedback/{} payload={}", method, p);
        return handleMessageFeedback(method, p, rpcId);
    }

    /** messageFeedback 处理（dispatch 点号 + REST 斜杠共用）：接真实 MessageFeedbackService。 */
    private Map<String, Object> handleMessageFeedback(String method, Map<String, Object> p, String rpcId) {
        var feedbackOpt = holder.context().get(com.deepseek.dsh.feedback.MessageFeedbackService.class);
        if (feedbackOpt.isEmpty()) return response(rpcId, err("internal", "message feedback service not registered"));
        var feedback = feedbackOpt.get();
        try {
            // 该 Remote 方法的业务结果本身是 {ok,value}|{ok,error} 联合（对应 TS 端的
            // MessageFeedback{List|Put|Delete}Result），因此必须整体嵌套进 RPC 载体的
            // value 字段（双层包裹），前端 controller 才能以 carried.value 读到它。
            return switch (method) {
                case "list" -> feedbackOk(rpcId, ok(Map.of("items",
                        safeFeedbackItems(feedback, String.valueOf(p.getOrDefault("sessionId", ""))))));
                case "put" -> {
                    var item = feedback.put(
                            SessionId.of(String.valueOf(p.getOrDefault("sessionId", ""))),
                            String.valueOf(p.getOrDefault("messageId", "")),
                            com.deepseek.dsh.feedback.FeedbackRating.of(String.valueOf(p.getOrDefault("rating", ""))),
                            p.get("note") == null ? null : String.valueOf(p.get("note")),
                            p.get("ifVersion") == null ? null : String.valueOf(p.get("ifVersion")));
                    yield feedbackOk(rpcId, ok(feedbackItem(item)));
                }
                case "delete" -> {
                    feedback.delete(
                            SessionId.of(String.valueOf(p.getOrDefault("sessionId", ""))),
                            String.valueOf(p.getOrDefault("messageId", "")),
                            p.get("ifVersion") == null ? null : String.valueOf(p.get("ifVersion")));
                    yield feedbackOk(rpcId, ok(Map.of("absent", true)));
                }
                default -> response(rpcId, err("internal", "unknown messageFeedback method: " + method));
            };
        } catch (com.deepseek.dsh.feedback.FeedbackException fe) {
            // 业务失败同样作为业务结果放入载体 value（carrier still ok:true），以便前端
            // 读到 version-conflict 的 current 并就地协商，而不是降级为通用传输失败。
            return feedbackOk(rpcId, feedbackReject(fe));
        } catch (RuntimeException re) {
            return response(rpcId, err("internal", re.getMessage()));
        }
    }

    /** 把 messageFeedback 的业务结果整体塞进 RPC 载体的 value（双层包裹）。 */
    private static Map<String, Object> feedbackOk(String rpcId, Map<String, Object> businessResult) {
        return response(rpcId, ok(businessResult));
    }

    /** 构造 messageFeedback 的业务失败结果 {ok:false,error}，version-conflict 携带 current。 */
    private static Map<String, Object> feedbackReject(com.deepseek.dsh.feedback.FeedbackException fe) {
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", feedbackWireCode(fe.code()));
        error.put("message", fe.getMessage());
        error.put("details", Map.of());
        if (fe.code() == com.deepseek.dsh.feedback.FeedbackException.Code.VERSION_CONFLICT
                && fe.conflictingCurrent() != null) {
            error.put("current", feedbackItem(fe.conflictingCurrent()));
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", error);
        return result;
    }

    /** Java 后端无 Cordis Loader，但把 ~40 个已装配模块作为插件清单项返回，使前端 Plugins 页能列出它们。 */
    private static Map<String, Object> pluginInventorySnapshot() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : PLUGIN_MODULES) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("entryId", name);
            e.put("moduleName", name);
            e.put("enabled", true);
            e.put("fiberPhase", "active");
            entries.add(e);
        }
        return Map.of("entries", entries);
    }

    /** 已装配的 Java 模块（对应原版 TS 的 @deepseek-ai/dsh-* 插件包）。 */
    private static final List<String> PLUGIN_MODULES = List.of(
            "dsh-core", "dsh-session", "dsh-session-sqlite", "dsh-tools", "dsh-llm", "dsh-agent",
            "dsh-capability-shell", "dsh-capability-fs", "dsh-capability-web", "dsh-terminal",
            "dsh-compaction", "dsh-subagent", "dsh-goal", "dsh-plan", "dsh-workflow",
            "dsh-code-runtime", "dsh-lsp", "dsh-interaction", "dsh-mcp", "dsh-sandbox",
            "dsh-jobs", "dsh-todo", "dsh-guard", "dsh-context", "dsh-credentials",
            "dsh-settings", "dsh-storage", "dsh-spill", "dsh-skill", "dsh-subprocess",
            "dsh-attachment", "dsh-workspace", "dsh-feedback", "dsh-schedule", "dsh-teams",
            "dsh-telemetry", "dsh-acp", "dsh-sdk", "dsh-web", "dsh-app");

    // ---- host.describe ----

    private Map<String, Object> hostDescribe() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", "0.1.2-alpha.1");
        v.put("cwd", System.getProperty("user.dir"));
        v.put("attachedSessions", 0);
        v.put("home", System.getProperty("user.home") + "/.dsh");
        v.put("canOpenPath", false);
        tryModelProfile(v);
        return v;
    }

    private void tryModelProfile(Map<String, Object> v) {
        try {
            var ctx = holder.context();
            ctx.get(com.deepseek.dsh.llm.config.ModelProfileStore.class).ifPresent(store -> {
                if (store.activeId() != null) {
                    store.profiles().stream().filter(p -> p.id().equals(store.activeId())).findFirst()
                            .ifPresent(p -> { v.put("provider", "openai-compatible"); v.put("model", p.model()); });
                }
            });
        } catch (Exception ignored) { /* 桥接未就绪时省略 */ }
    }

    // ---- session.create / list ----

    private Map<String, Object> sessionCreate(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        Context ctx = holder.context();
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog slog = sessions.create();
        SessionId sid = slog.sessionId();
        String workspaceId = p.get("workspaceId") != null ? String.valueOf(p.get("workspaceId")) : null;
        // SessionManager 已广播 host/session-added（SessionCreatedBroadcaster 订阅事件推送），此处关联工作区 + 订阅进会话
        if (workspaceId != null) {
            Map<String, Object> wv = workspaces.attachSession(workspaceId, sid.value());
            if (wv != null) remoteMux.broadcastWorkspaceFrame(Map.of("type", "upsert", "workspace", wv));
        }
        String model = currentModelName();
        Map<String, Object> selection = Map.of("provider", "openai-compatible", "model", model);
        remoteMux.broadcastControlFrame(Map.of(
                "type", "projection",
                "sessionId", sid.value(),
                "key", "modelSelection",
                "value", Map.of("lastUsed", selection, "next", selection),
                "seq", -1));
        remoteMux.broadcastControlFrame(Map.of(
                "type", "projection",
                "sessionId", sid.value(),
                "key", "agentPreset",
                "value", defaultPreset,
                "seq", -1));
        downlink.sendMuxFrame(uuid(), muxFrame("session/subscribed",
                Map.of("sessionId", sid.value(), "lastSeq", seq.get() - 1)));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sessionId", sid.value());
        return v;
    }

    /** workspace.create({path})：采纳真实目录，建工作区，推 host/workspace-changed。 */
    private Map<String, Object> workspaceCreate(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String path = p.get("path") != null ? String.valueOf(p.get("path")) : System.getProperty("user.dir");
        java.io.File dir = new java.io.File(path);
        if (!dir.isDirectory()) {
            throw new RuntimeException("workspace-invalid-path: " + path + " is not a directory");
        }
        Map<String, Object> result = workspaces.ensure(path);
        Map<String, Object> wsView = (Map<String, Object>) result.get("workspace");
        remoteMux.broadcastWorkspaceFrame(Map.of("type", "upsert", "workspace", wsView));
        return result;
    }

    private Map<String, Object> sessionList() {
        Context ctx = holder.context();
        Sessions sessions = ctx.require(Sessions.class);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionId id : sessions.list()) {
            SessionLog sl = sessions.getOrCreate(id);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sessionId", id.value());
            String title = customTitles.getOrDefault(id.value(), generateTitle(sl));
            long lastSeq = sl.lastSeq();
            long lastTime = sl.events().isEmpty() ? 0 : sl.events().get(sl.events().size() - 1).time();
            s.put("projections", Map.of("asOfSeq", lastSeq, "values", Map.of("title", title, "blank", sl.size() == 0)));
            s.put("updatedAt", lastTime > 0 ? lastTime : System.currentTimeMillis());
            s.put("running", runningTurns.containsKey(id.value()));
            s.put("blank", sl.size() == 0);
            String wsPath = workspaces.findSessionWorkspacePath(id.value());
            s.put("cwd", wsPath != null ? wsPath : System.getProperty("user.dir"));
            items.add(s);
        }
        return Map.of("items", items);
    }

    private static String generateTitle(SessionLog sl) {
        return TurnOrchestrator.generateTitle(sl);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionRename(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sid = strOf(p.get("sessionId"));
        String title = strOf(p.get("title"));
        title = title.replaceAll("\\s*\\(\\d+\\)$", "").replaceAll("\\s*（\\d+）$", "");
        if (!sid.isEmpty() && !title.isEmpty()) {
            customTitles.put(sid, title);
            sendSessionProjection(sid, "title", title);
        }
        return Map.of("title", title);
    }

    /** session.cancel({sessionId})：中断正在运行的 agent turn 线程，使对话框「停止生成」生效。 */
    private Map<String, Object> sessionCancel(Object payload) {
        String sid = strField(payload, "sessionId");
        Thread t = runningTurns.remove(sid);
        if (t != null) t.interrupt();
        cancelledSessions.add(sid);
        return Map.of("accepted", true);
    }

    /** session.fork({sessionId})：复制父会话的全部事件到新会话（保留记忆）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionFork(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String parentSid = String.valueOf(p.getOrDefault("sessionId", ""));
        try {
            SessionLog child = orchestrator().forkSession(parentSid, this::sendSessionEvent);
            // 将子会话挂到父会话所在的工作区
            String parentWsId = workspaces.findSessionWorkspace(parentSid);
            String childCwd = System.getProperty("user.dir");
            if (parentWsId != null) {
                Map<String, Object> attached = workspaces.attachSession(parentWsId, child.sessionId().value());
                String wsPath = workspaces.findSessionWorkspacePath(child.sessionId().value());
                if (wsPath != null) childCwd = wsPath;
                remoteMux.broadcastWorkspaceFrame(Map.of("type", "upsert", "workspace", attached));
            }
            String childTitle = TurnOrchestrator.generateTitle(child);
            long childSeq = child.lastSeq();
            remoteMux.broadcastControlFrame(Map.of(
                    "type", "projection",
                    "sessionId", child.sessionId().value(),
                    "key", "title",
                    "value", childTitle,
                    "seq", childSeq));
            // 分叉响应直接携带与刷新（控制基线）一致的标题，供前端立即落地投影，
            // 避免 live 页先渲染出错误标题、直到刷新/重基线才被纠正。
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("sessionId", child.sessionId().value());
            resp.put("title", childTitle);
            return resp;
        } catch (Exception e) {
            log.warn("session.fork failed: {}", e.toString());
            return Map.of("sessionId", UUID.randomUUID().toString());
        }
    }

    private Map<String, Object> assistantMessageData(String id, String content) {
        return Map.of("message", Map.of(
                "id", id, "content", List.of(textPart(content)),
                "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", currentModelName())));
    }

    private Map<String, Object> sessionModels() {
        String model = currentModelName();
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("provider", "openai-compatible");
        sel.put("model", model);
        List<Map<String, Object>> models = selectableModels(model);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "openai-compatible"); group.put("name", "OpenAI Compatible");
        group.put("models", models);
        return Map.of("current", sel, "routable", true, "groups", List.of(group), "failures", List.of());
    }

    private Map<String, Object> modelCatalog() {
        String model = currentModelName();
        Map<String, Object> defaultSel = new LinkedHashMap<>();
        defaultSel.put("provider", "openai-compatible");
        defaultSel.put("model", model);
        List<Map<String, Object>> models = selectableModels(model);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "openai-compatible");
        group.put("name", "OpenAI Compatible");
        group.put("models", models);
        return Map.of("default", defaultSel, "routableProviders", List.of("openai-compatible"),
                "groups", List.of(group), "failures", List.of());
    }

    /** /model 可选模型：仅 active profile 配置过的 models 数组（+ 当前模型，按 id 去重）。
     *  不拉 provider /models 发现到的模型——只切换用户已配置的模型。 */
    private List<Map<String, Object>> selectableModels(String currentModel) {
        List<Map<String, Object>> models = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        ModelProfile ap = activeProfile();
        if (ap != null && ap.models() != null) {
            for (Map<String, Object> mm : ap.models()) {
                Object idObj = mm.getOrDefault("id", mm.get("name"));
                String id = idObj == null ? "" : String.valueOf(idObj);
                if (id.isEmpty() || "null".equals(id) || !seen.add(id)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                Object nameObj = mm.get("name");
                entry.put("name", nameObj == null || String.valueOf(nameObj).isEmpty() ? id : String.valueOf(nameObj));
                models.add(entry);
            }
        }
        if (currentModel != null && !currentModel.isBlank() && seen.add(currentModel)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", currentModel); m.put("name", currentModel);
            models.add(m);
        }
        return models;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionSelectModel(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sessionId = String.valueOf(p.getOrDefault("sessionId", ""));
        String model = String.valueOf(p.getOrDefault("model", currentModelName()));
        if (model.isEmpty() || "null".equals(model)) model = currentModelName();
        if (!sessionId.isEmpty() && !"null".equals(sessionId)) sessionModelSelection.put(sessionId, model);
        // agent 从 active profile 读模型，故把 active profile 的 model 字段切到所选模型，使聊天实际用它
        ModelProfileStore s = storeOrNone();
        ModelProfile ap = activeProfile();
        if (s != null && ap != null && !model.equals(ap.model())) {
            s.upsert(new ModelProfile(ap.id(), ap.displayName(), ap.apiKey(), ap.baseUrl(), model, ap.models(), ap.route()));
            s.setActive(ap.id());
        }
        // 广播 modelSelection 投影，前端实时反映所选模型（此前需刷新重读 baseline 才看到）
        Map<String, Object> selection = Map.of("provider", "openai-compatible", "model", model);
        Map<String, Object> modelSelection = Map.of("lastUsed", selection, "next", selection);
        if (!sessionId.isEmpty() && !"null".equals(sessionId)) {
            sendSessionProjection(sessionId, "modelSelection", modelSelection);
        }
        return Map.of("selected", selection);
    }

    private TurnOrchestrator orchestrator() {
        return new TurnOrchestrator(holder.context(), holder.agent(), workspaces);
    }

    // ---- session.prompt → agent → frames ----

    private Map<String, Object> sessionPrompt(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sessionId = String.valueOf(p.getOrDefault("sessionId", UUID.randomUUID().toString()));
        String text = extractPromptText(p);
        TurnOrchestrator orch = orchestrator();
        int turn = orch.nextTurn(sessionId);

        // 未分组会话自动按「年-月-日-时」分配工作区，便于按时间批量查询
        if (workspaces.findSessionWorkspace(sessionId) == null) {
            String dateHour = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
            Map<String, Object> wsResult = workspaces.ensure(dateHour);
            if (wsResult.get("workspace") instanceof Map<?, ?> wv) {
                String wsId = String.valueOf(wv.get("workspaceId"));
                Map<String, Object> attached = workspaces.attachSession(wsId, sessionId);
                if (attached != null) {
                    try { downlink.sendHostFrame(uuid(), hostFrame("host/workspace-changed", Map.of("workspace", attached))); } catch (Exception e) { log.debug("runTurn host/workspace-changed: {}", e.toString()); }
                }
            }
        }

        String model = sessionModelSelection.getOrDefault(sessionId, currentModelName());
        String rpcId = strOf(p.get("requestId"));
        orch.prepareTurn(sessionId, text, turn, model, rpcId, this::sendSessionEvent);
        sendSessionProjection(sessionId, "title", text.length() > 40 ? text.substring(0, 40) + "…" : text);
        remoteMux.broadcastEmit("api-session/status", new Object[]{sessionId, true});
        try { downlink.sendHostFrame(uuid(), hostFrame("host/session-status",
                Map.of("sessionId", sessionId, "running", true))); } catch (Exception e) { log.debug("runTurn host/session-status(running): {}", e.toString()); }

        Thread turnThread = Thread.startVirtualThread(() -> {
            orch.runAgent(sessionId, text, turn, model, this::sendSessionEvent);
            runningTurns.remove(sessionId);
            remoteMux.broadcastEmit("api-session/status", new Object[]{sessionId, false});
            try { downlink.sendHostFrame(uuid(), hostFrame("host/session-status",
                    Map.of("sessionId", sessionId, "running", false))); } catch (Exception e) { log.debug("runTurn host/session-status(done): {}", e.toString()); }
        });
        runningTurns.put(sessionId, turnThread);
        return Map.of("accepted", true);
    }

    /** session.history：把 dsh-java 投影消息映射为 harness 事件信封（user/message + turn/assistant-message），供 UI 重放历史。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionHistory(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sessionId = String.valueOf(p.getOrDefault("sessionId", ""));
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog slog = sessions.getOrCreate(SessionId.of(sessionId));
            for (SessionEvent e : slog.events()) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", e.type());
                event.put("seq", e.seq());
                event.put("time", e.time());
                event.put("data", e.data());
                if (e.surfaceOp() != null && !e.surfaceOp().isEmpty()) event.put("surfaceOp", e.surfaceOp());
                events.add(Map.of("type", "event", "event", event));
            }
        } catch (Exception e) {
            log.warn("session.history failed: {}", e.toString());
        }
        String histTitle = "新会话";
        long lastSeq = -1;
        for (var entry : events) {
            if (!(entry instanceof Map<?, ?> m)) continue;
            if (!(m.get("event") instanceof Map<?, ?> e)) continue;
            Object seqVal = e.get("seq");
            if (seqVal instanceof Number n) lastSeq = n.longValue();
            if ("user/message".equals(e.get("type")) && e.get("data") instanceof Map<?, ?> d
                    && d.get("content") instanceof List<?> parts && !parts.isEmpty()
                    && parts.get(0) instanceof Map<?, ?> partMap && "text".equals(partMap.get("type"))
                    && partMap.get("text") instanceof String partText && !partText.isBlank()) {
                histTitle = partText.length() > 40 ? partText.substring(0, 40) + "…" : partText;
            }
        }
        return Map.of("events", events, "hasMore", false,
                "projections", Map.of("asOfSeq", lastSeq, "values", Map.of("title", histTitle)));
    }

    /**
     * session.page：0.1.2 的 SessionPage 线格式 ——
     * {@code {records: SessionHistoryRecord[], hasMore: boolean}}，
     * 其中 records 是打包的 chunkrow/* 行（连续 ≥3 个同类 chunk 压一行）
     * 加上其他事件原样透传。复用 {@link #sessionHistory} 的事件构建逻辑。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionPage(Object rawPayload) {
        Map<String, Object> payload = rawPayload instanceof Map ? (Map<String, Object>) rawPayload : Map.of();
        String sessionId = extractSessionIdFromArgs(payload);
        Map<String, Object> histResult = sessionHistory(Map.of("sessionId", sessionId));
        List<Map<String, Object>> events = (List<Map<String, Object>>) histResult.get("events");
        List<Map<String, Object>> records = new ArrayList<>();
        for (var entry : events) {
            if (entry.get("event") instanceof Map<?, ?> e) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("type", "event");
                Map<String, Object> ev = new LinkedHashMap<>((Map<String, Object>) e);
                rec.put("event", ev);
                records.add(rec);
            }
        }
        return Map.of("records", records, "hasMore", false);
    }

    /** 从 0.1.2 RPC 的 args.address.sessionId 提取会话 ID（兼容旧直传格式）。 */
    @SuppressWarnings("unchecked")
    private static String extractSessionIdFromArgs(Map<String, Object> payload) {
        Object args = payload.get("args");
        if (args instanceof Map<?, ?> a) {
            Object address = a.get("address");
            if (address instanceof Map<?, ?> addr && addr.get("sessionId") instanceof String sid) return sid;
            if (a.get("sessionId") instanceof String sid) return sid;
        }
        if (payload.get("sessionId") instanceof String sid) return sid;
        return "";
    }

    private static final int MIN_RUN = 3;

    /**
     * 把事件列表打包成 SessionHistoryRecord[]：
     * 连续 ≥MIN_RUN 个同类（text-delta/reasoning-delta/tool-call-delta）同块
     * assistant/chunk 压成一个 chunkrow/* 行；其余事件原样透传。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> packChunkRuns(List<Map<String, Object>> entries) {
        List<Map<String, Object>> records = new ArrayList<>();
        List<Map<String, Object>> run = new ArrayList<>();
        String runKind = null;

        for (Map<String, Object> entry : entries) {
            Object eventObj = entry.get("event");
            if (!(eventObj instanceof Map<?, ?> em)) { records.add(entry); continue; }
            Map<String, Object> event = (Map<String, Object>) em;
            String eventType = String.valueOf(event.get("type"));
            if (!"assistant/chunk".equals(eventType)) {
                flushRun(records, run, runKind);
                runKind = null;
                records.add(Map.of("type", "event", "event", event));
                continue;
            }
            Map<String, Object> data = (Map<String, Object>) event.get("data");
            if (data == null) { flushRun(records, run, runKind); runKind = null; records.add(Map.of("type", "event", "event", event)); continue; }
            Map<String, Object> chunk = (Map<String, Object>) data.get("chunk");
            if (chunk == null) { flushRun(records, run, runKind); runKind = null; records.add(Map.of("type", "event", "event", event)); continue; }
            String kind = String.valueOf(chunk.get("type"));
            if (kind.equals(runKind) && !run.isEmpty()) {
                run.add(entry);
            } else {
                flushRun(records, run, runKind);
                run.clear();
                run.add(entry);
                runKind = kind;
            }
        }
        flushRun(records, run, runKind);
        return records;
    }

    @SuppressWarnings("unchecked")
    private static void flushRun(List<Map<String, Object>> records, List<Map<String, Object>> run, String kind) {
        if (run.isEmpty() || kind == null) return;
        if (run.size() < MIN_RUN) {
            for (var entry : run) {
                Object event = entry.get("event");
                records.add(Map.of("type", "event", "event", event));
            }
            return;
        }
        Map<String, Object> first = (Map<String, Object>) run.get(0).get("event");
        long seq0 = ((Number) first.get("seq")).longValue();
        long time0 = ((Number) first.get("time")).longValue();
        Map<String, Object> firstData = (Map<String, Object>) first.get("data");
        Map<String, Object> firstChunk = (Map<String, Object>) firstData.get("chunk");
        int turn = ((Number) firstData.get("turn")).intValue();
        int step = ((Number) firstData.get("step")).intValue();
        int index = firstChunk.get("index") instanceof Number n ? n.intValue() : 0;

        List<String> texts = new ArrayList<>();
        List<Long> dt = new ArrayList<>();
        long prevTime = time0;
        for (var entry : run) {
            Map<String, Object> ev = (Map<String, Object>) entry.get("event");
            Map<String, Object> d = (Map<String, Object>) ev.get("data");
            Map<String, Object> c = (Map<String, Object>) d.get("chunk");
            texts.add(String.valueOf(c.get("text")));
            long t = ((Number) ev.get("time")).longValue();
            dt.add(t - prevTime);
            prevTime = t;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turn", turn);
        data.put("step", step);
        data.put("index", index);
        data.put("dt", dt);
        data.put("texts", texts);

        String rowType = switch (kind) {
            case "text-delta" -> "chunkrow/text-chunks";
            case "reasoning-delta" -> "chunkrow/reasoning-chunks";
            case "tool-call-delta" -> "chunkrow/tool-call-chunks";
            default -> null;
        };
        if (rowType == null) {
            for (var entry : run) records.add(Map.of("type", "event", "event", entry.get("event")));
            return;
        }
        records.add(Map.of("type", "chunks", "event", Map.of(
                "type", rowType, "seq", seq0, "time", time0, "data", data)));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 工具调用参数 Map → JSON 字符串（历史重放的 tool/call.arguments 需字符串，对齐实时流）。 */
    private static String toolArgsJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        try { return JSON_MAPPER.writeValueAsString(args); }
        catch (Exception ex) { return "{}"; }
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "text"); b.put("text", text);
        return b;
    }

    private static Map<String, Object> historyEntry(Map<String, Object> event) {
        return Map.of("event", event);
    }

    private static Map<String, Object> envelope(String type, long seq, long time, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("seq", seq);
        e.put("time", time);
        e.put("data", data);
        if (isSurfaceMessageEvent(type)) e.put("surfaceOp", "append");
        return e;
    }

    private String extractPromptText(Map<String, Object> p) {
        Object content = p.get("content");
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> m && "text".equals(m.get("type")) && m.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        if (content instanceof String s) return s;
        Object message = p.get("message");
        if (message instanceof String ms) return ms;
        return "";
    }

    // ---- 启动期只读空值 ----

    private Map<String, Object> valueOf(String method) {
        return switch (method) {
            case "session.search" -> Map.of("items", List.of(), "hasMore", false);
            case "session.attachment" -> Map.of();
            case "session.updateQueue" -> Map.of("accepted", true);
            case "session.selectModel" -> Map.of("selected", Map.of("provider", "openai-compatible", "model", currentModelName()));
            case "workspace.list" -> Map.of("items", List.of(defaultWorkspace()), "archivedSessionIds", List.of());
            case "workspace.create" -> Map.of("workspace", Map.of("workspaceId", UUID.randomUUID().toString(), "title", "workspace", "sessionIds", List.of()), "created", true);
            case "settings.describe" -> settingsDescribe();
            case "settings.openDocument", "settings.update", "settings.replace", "settings.mutate" -> Map.of();
            case "llm.providers" -> Map.of("providers", List.of(Map.of(
                    "provider", "openai-compatible", "displayName", "OpenAI Compatible",
                    "settingsNs", "llm", "settingsPath", List.of("llm"), "active", true)));
            case "llm.models", "llm.discoverModels" -> Map.of("models", List.of(), "failures", List.of());
            case "skill.list" -> Map.of("skills", List.of());
            case "credentials.describe" -> Map.of("credentials", Map.of());
            case "credentials.set", "credentials.unset" -> Map.of();
            case "host.pickDirectory" -> Map.of("path", (Object) null);
            case "host.createDirectory" -> Map.of("path", System.getProperty("user.dir"));
            case "host.openPath" -> Map.of("opened", true);
            case "subagent.list", "subagent.history" -> Map.of("items", List.of());
            case "subagent.prompt", "subagent.interrupt" -> Map.of();
            case "goal.create", "goal.edit", "goal.pause", "goal.resume", "goal.complete", "goal.clear" -> Map.of();
            default -> Map.of();
        };
    }

    /** skill.list：列出已发现技能（接真实 SkillRegistry；字段对齐 TS：name/description/whenToUse?/modelInvocable，过滤 user-invocable）。 */
    private Map<String, Object> skillList() {
        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
        Context ctx = holder.context();
        ctx.get(com.deepseek.dsh.skill.SkillService.class).ifPresent(skills -> {
            for (var s : skills.list(null)) {
                if (s.invocation() == null || !s.invocation().userInvocable()) continue;
                Map<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("name", s.name());
                e.put("description", s.description() == null ? "" : s.description());
                if (s.whenToUse() != null && s.whenToUse().isPresent()) e.put("whenToUse", s.whenToUse().get());
                e.put("modelInvocable", s.invocation() != null && s.invocation().modelInvocable());
                entries.add(e);
            }
        });
        return Map.of("skills", entries);
    }

    /** host.listDirectory：真实目录列表（供前端 browse 选择器导航 → 选目录建工作区）。 */
    private Map<String, Object> listDirectory(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String home = System.getProperty("user.home");
        String path = p.get("path") != null ? String.valueOf(p.get("path")) : home;
        java.io.File dir = new java.io.File(path);
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        if (dir.isDirectory()) {
            java.io.File[] children = dir.listFiles();
            if (children != null) {
                for (java.io.File c : children) {
                    if (!c.isDirectory()) continue;
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", c.getName());
                    e.put("path", c.getAbsolutePath());
                    e.put("hidden", c.getName().startsWith("."));
                    entries.add(e);
                    if (entries.size() >= 500) { truncated = true; break; }
                }
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("path", dir.getAbsolutePath());
        v.put("home", home);
        v.put("crumbs", List.of());
        v.put("entries", entries);
        v.put("truncated", truncated);
        return v;
    }

    private Map<String, Object> settingsDescribe() {
        // writable=true 使前端「设置→模型」页解锁写入控件；hasDocument=true 表示存在可写文档。
        // 命名空间 llm-pi-ai 反映模型档案；ui-onboarding 承载欢迎声明确认版本（缺失则页面进入 ready 而非 unavailable）。
        // agent-loop / bash / web-search-deepseek / subagent：复刻 harness 设置页四个插件配置卡片。
        return Map.of(
                "writable", true,
                "hasDocument", true,
                "namespaces", List.of(llmPiAiNamespace(), uiOnboardingNamespace(), agentPresetsNamespace(),
                        agentLoopNamespace(), bashNamespace(), webSearchNamespace(), subagentNamespace()));
    }

    private Map<String, Object> openSettingsDocument() {
        try {
            java.io.File f = new java.io.File(System.getProperty("user.home"), ".dsh/model-config.json");
            if (f.isFile() && java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(f);
            }
        } catch (Exception ignored) { /* 无桌面环境（headless）时仅回告 opened=true，符合 loopback 契约而不报错 */ }
        return Map.of("opened", true);
    }

    /** 推一个 host/remote-event 转发帧，触发前端 mirror/store 刷新（settings/document-updated 等）。 */
    private void pushRemoteEvent(String event, Object... args) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "host/remote-event");
        f.put("event", event);
        f.put("args", List.of(args));
        downlink.sendHostFrame(uuid(), f);
    }

    /** settings.mutate/update/replace：llm-pi-ai 翻译为 ModelProfileStore；其余命名空间经 SettingsService 持久化扁平字段后回显。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> settingsWrite(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String ns = String.valueOf(p.getOrDefault("ns", "ui-onboarding"));
        return "llm-pi-ai".equals(ns) ? llmPiAiMutate(p) : genericSettingsWrite(p, ns);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> genericSettingsWrite(Map<String, Object> p, String ns) {
        SettingsService s = settingsService();
        Map<String, Object> value = new LinkedHashMap<>();
        if (s != null) s.getAll(ns).forEach((k, v) -> value.put(k, v));
        Object patch = p.get("patch");
        if (patch instanceof Map<?, ?> pm) for (Object k : pm.keySet()) {
            String key = String.valueOf(k);
            value.put(key, pm.get(k));
            persistSet(s, ns, key, pm.get(k));
        }
        Object section = p.get("section");
        if (section instanceof Map<?, ?> sm) for (Object k : sm.keySet()) {
            String key = String.valueOf(k);
            value.put(key, sm.get(k));
            persistSet(s, ns, key, sm.get(k));
        }
        Object ops = p.get("ops");
        if (ops instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> op)) continue;
                String opName = String.valueOf(op.get("op"));
                Object pathObj = op.get("path");
                if (!(pathObj instanceof List<?> path) || path.isEmpty()) continue;
                String[] keys = path.stream().map(String::valueOf).toArray(String[]::new);
                if ("set".equals(opName)) {
                    setNested(value, keys, op.get("value"));
                    if (keys.length == 1) persistSet(s, ns, keys[0], op.get("value"));
                } else if ("unset".equals(opName)) {
                    unsetNested(value, keys);
                }
            }
        }
        pushRemoteEvent("settings/document-updated", ns, 1);
        return namespaceView(ns, value);
    }

    private static void persistSet(SettingsService s, String ns, String field, Object val) {
        if (s == null) return;
        String stored;
        if (val == null) stored = "";
        else if (val instanceof String) stored = (String) val;
        else if (val instanceof Number || val instanceof Boolean) stored = String.valueOf(val);
        else {
            try { stored = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(val); }
            catch (Exception e) { stored = String.valueOf(val); }
        }
        s.set(ns, field, stored);
    }

    /** 数字字段按 number 读回（SettingsService 只存 String，故读回需强转）。 */
    private static Double numOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.valueOf(v); } catch (Exception e) { return null; }
    }

    private static Boolean boolOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        return Boolean.valueOf(v);
    }

    /** allowedModels 等 array 字段以 JSON 字符串持久化，读回还原为 List。 */
    private static List<Map<String, Object>> jsonListOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(v);
            if (!n.isArray()) return null;
            List<Map<String, Object>> out = new ArrayList<>();
            for (var e : n) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.path("id").asText(""));
                if (!e.path("name").asText("").isEmpty()) m.put("name", e.path("name").asText(""));
                out.add(m);
            }
            return out;
        } catch (Exception ex) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static void setNested(Map<String, Object> root, String[] keys, Object val) {
        Map<String, Object> cur = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = cur.get(keys[i]);
            if (!(next instanceof Map)) { next = new LinkedHashMap<>(); cur.put(keys[i], next); }
            cur = (Map<String, Object>) next;
        }
        cur.put(keys[keys.length - 1], val);
    }

    @SuppressWarnings("unchecked")
    private static void unsetNested(Map<String, Object> root, String[] keys) {
        Map<String, Object> cur = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = cur.get(keys[i]);
            if (!(next instanceof Map)) return;
            cur = (Map<String, Object>) next;
        }
        cur.remove(keys[keys.length - 1]);
    }

    private Map<String, Object> namespaceView(String ns, Map<String, Object> value) {
        return namespaceView(ns, value, Map.of());
    }

    private Map<String, Object> namespaceView(String ns, Map<String, Object> value, Map<String, Object> schema) {
        return namespaceView(ns, value, schema, Map.of(), Map.of());
    }

    /** 带 base/user 层的 namespace 视图：llm-pi-ai 设 user=providers、base={} 使前端判定「用户层持有 → 可删除」(removable=true)，且编辑器从 user 预填当前 profile。 */
    private Map<String, Object> namespaceView(String ns, Map<String, Object> value, Map<String, Object> schema,
                                              Map<String, Object> base, Map<String, Object> user) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ns", ns);
        v.put("schema", schema);
        v.put("value", value);
        v.put("base", base);
        v.put("user", user);
        v.put("applies", "live");
        v.put("secrets", List.of());
        v.put("revision", 1);
        return v;
    }

    /** llm-pi-ai 的 schemastery schema 信封：providers.<route> → {apiKeyEnv,displayName,api,baseURL,models[]}。
     *  前端 schema-operations 用 nodeAtPath 解析 providers.<route>.<field> 与 api 协议清单；每个节点带 meta:{} 避免 validate 访问 .meta 报错。 */
    private static Map<String, Object> llmPiAiSchema() {
        Map<String, Object> modelDict = new LinkedHashMap<>();
        modelDict.put("id", schemaNode("string"));
        modelDict.put("name", schemaNode("string"));
        modelDict.put("contextWindow", schemaNode("number"));
        modelDict.put("maxTokens", schemaNode("number"));
        Map<String, Object> profileDict = new LinkedHashMap<>();
        profileDict.put("apiKeyEnv", schemaNode("string"));
        profileDict.put("displayName", schemaNode("string"));
        profileDict.put("api", schemaNode("union", "list", List.of(
                schemaNode("const", "value", "openai-completions"),
                schemaNode("const", "value", "openai-responses"),
                schemaNode("const", "value", "anthropic-messages"))));
        profileDict.put("baseURL", schemaNode("string"));
        profileDict.put("models", schemaNode("array", "inner", schemaNode("object", "dict", modelDict)));
        Map<String, Object> providers = schemaNode("dict", "inner", schemaNode("object", "dict", profileDict));
        Map<String, Object> rootDict = new LinkedHashMap<>();
        rootDict.put("providers", providers);
        return schemaNode("object", "dict", rootDict);
    }

    private static Map<String, Object> schemaNode(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("meta", new LinkedHashMap<>());
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private ModelProfileStore storeOrNone() {
        try {
            return holder.context().get(ModelProfileStore.class).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private SettingsService settingsService() {
        try {
            return holder.context().get(SettingsService.class).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ModelProfile activeProfile() {
        ModelProfileStore s = storeOrNone();
        return s == null ? null : s.active().orElse(null);
    }

    /** route → 档案 id；ensureRoutes 已为每个档案建立持久化 route 并填入此表。 */
    private String profileIdForRoute(String route) {
        ensureRoutes();
        return routeToProfileId.get(route);
    }

    /** 为每个档案确保有 route：无 route 则从 model/displayName 派生唯一路由并回写，再重建 routeToProfileId。
     *  每次调用都跑（幂等：已有 route 的档案跳过 upsert）—— 这样会话中途新增的档案也能立即拿到 route，
     *  llm.providers 随即列出，UI 无需重启即可显示（修复「创建提供方后不显示」）。 */
    private void ensureRoutes() {
        ModelProfileStore s = storeOrNone();
        if (s == null) return;
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (ModelProfile p : s.profiles()) {
            String route = p.route();
            if (route == null || route.isBlank()) {
                String base = sanitizeRoute(p.model().isBlank() ? p.displayName() : p.model());
                route = base;
                int n = 1;
                while (taken.contains(route)) route = base + "-" + (++n);
                s.upsert(new ModelProfile(p.id(), p.displayName(), p.apiKey(), p.baseUrl(), p.model(), p.models(), route));
            }
            taken.add(route);
            routeToProfileId.put(route, p.id());
        }
    }

    private static String sanitizeRoute(String s) {
        if (s == null || s.isBlank()) return "provider";
        String r = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (r.isEmpty() || !Character.isLetter(r.charAt(0))) r = "p" + r;
        return r;
    }

    private ModelProfile profileForRoute(String route) {
        ModelProfileStore s = storeOrNone();
        if (s == null) return null;
        String id = profileIdForRoute(route);
        if (id == null) return null;
        return s.profiles().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    private static String deriveKeyRef(String route) {
        return route.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_API_KEY";
    }

    private Map<String, Object> llmPiAiNamespace() {
        ensureRoutes();
        ModelProfileStore s = storeOrNone();
        Map<String, Object> providers = new LinkedHashMap<>();
        if (s != null) {
            for (ModelProfile p : s.profiles()) {
                String route = p.route();
                if (route == null || route.isBlank()) continue;
                providers.put(route, profileView(route, p));
            }
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("providers", providers);
        return namespaceView("llm-pi-ai", value, llmPiAiSchema(), Map.of(), value);
    }

    private Map<String, Object> uiOnboardingNamespace() {
        SettingsService s = settingsService();
        Map<String, Object> value = new LinkedHashMap<>();
        if (s != null) s.getAll("ui-onboarding").forEach((k, v) -> value.put(k, v));
        return namespaceView("ui-onboarding", value);
    }

    private Map<String, Object> agentPresetsNamespace() {
        SettingsService s = settingsService();
        Map<String, Object> value = new LinkedHashMap<>();
        if (s != null) s.getAll("agent-presets").forEach((k, v) -> value.put(k, v));
        return namespaceView("agent-presets", value);
    }

    // ---- 复刻 harness 设置页四个插件配置卡片：agent-loop / bash / web-search-deepseek / subagent ----

    /** agent-loop：每 step 并行工具调用上限（harness 默认 10）。 */
    private Map<String, Object> agentLoopNamespace() {
        SettingsService s = settingsService();
        Double mpc = (s != null) ? numOrNull(s.getAll("agent-loop").get("maxParallelToolCalls")) : null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("maxParallelToolCalls", mpc != null ? mpc : 10);
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("maxParallelToolCalls", schemaNode("number"));
        return namespaceView("agent-loop", value, schemaNode("object", "dict", dict));
    }

    /** bash（终端，namespace=shell）：前台命令超时 ms + 每流输出上限 bytes。
     *  namespace 用 'shell' 与前端 BashCard 的 SHELL_NS 一致（卡片 key 须与 served ns 匹配才显示）。 */
    private Map<String, Object> bashNamespace() {
        SettingsService s = settingsService();
        Map<String, Object> value = new LinkedHashMap<>();
        if (s != null) {
            Map<String, String> all = s.getAll("shell");
            Double t = numOrNull(all.get("timeoutMs"));
            Double o = numOrNull(all.get("maxOutputBytes"));
            value.put("timeoutMs", t != null ? t : 120000);
            value.put("maxOutputBytes", o != null ? o : 64000);
        } else {
            value.put("timeoutMs", 120000);
            value.put("maxOutputBytes", 64000);
        }
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("timeoutMs", schemaNode("number"));
        dict.put("maxOutputBytes", schemaNode("number"));
        return namespaceView("shell", value, schemaNode("object", "dict", dict));
    }

    /** web-search-deepseek：搜索 provider 的 key 引用、端点、单次请求最大搜索数（harness 默认 5）。 */
    private Map<String, Object> webSearchNamespace() {
        SettingsService s = settingsService();
        Map<String, Object> value = new LinkedHashMap<>();
        if (s != null) {
            Map<String, String> all = s.getAll("web-search-deepseek");
            Double mu = numOrNull(all.get("maxUses"));
            value.put("apiKeyEnv", all.get("apiKeyEnv"));
            value.put("baseURL", all.get("baseURL"));
            value.put("maxUses", mu != null ? mu : 5);
        } else {
            value.put("maxUses", 5);
        }
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("apiKeyEnv", schemaNode("string"));
        dict.put("baseURL", schemaNode("string"));
        dict.put("maxUses", schemaNode("number"));
        return namespaceView("web-search-deepseek", value, schemaNode("object", "dict", dict));
    }

    /** subagent（namespace=subagent-model-selection）：是否对新会话启用模型面向的子路由选择 + 允许的子模型清单。 */
    private Map<String, Object> subagentNamespace() {
        SettingsService s = settingsService();
        Map<String, Object> value = new LinkedHashMap<>();
        if (s != null) {
            Map<String, String> all = s.getAll("subagent-model-selection");
            Boolean en = boolOrNull(all.get("enabled"));
            value.put("enabled", en != null ? en : false);
            value.put("allowedModels", jsonListOrNull(all.get("allowedModels")));
        } else {
            value.put("enabled", false);
        }
        Map<String, Object> modelDict = new LinkedHashMap<>();
        modelDict.put("id", schemaNode("string"));
        modelDict.put("name", schemaNode("string"));
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("enabled", schemaNode("boolean"));
        dict.put("allowedModels", schemaNode("array", "inner", schemaNode("object", "dict", modelDict)));
        return namespaceView("subagent-model-selection", value, schemaNode("object", "dict", dict));
    }

    private Map<String, Object> profileView(String route, ModelProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", p.displayName());
        m.put("apiKeyEnv", deriveKeyRef(route));
        m.put("api", "openai");
        m.put("baseURL", p.baseUrl());
        List<Map<String, Object>> models = new ArrayList<>();
        if (p.models() != null && !p.models().isEmpty()) {
            for (Map<String, Object> mm : p.models()) models.add(new LinkedHashMap<>(mm));
        } else if (!p.model().isBlank()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", p.model());
            mm.put("name", p.displayName());
            models.add(mm);
        }
        m.put("models", models);
        return m;
    }

    private List<Map<String, Object>> llmProviders() {
        ensureRoutes();
        ModelProfileStore s = storeOrNone();
        String activeId = s == null ? null : s.activeId();
        List<Map<String, Object>> providers = new ArrayList<>();
        if (s != null) {
            for (ModelProfile p : s.profiles()) {
                String route = p.route();
                if (route == null || route.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", route);
                m.put("name", p.displayName());
                providers.add(m);
            }
        }
        return providers;
    }

    private List<Map<String, Object>> llmConfigurableProviders() {
        List<Map<String, Object>> dir = new ArrayList<>();
        ModelProfileStore s = storeOrNone();
        if (s != null) {
            for (ModelProfile p : s.profiles()) {
                String route = p.route();
                if (route == null || route.isBlank()) continue;
                dir.add(configurableEntry(route, p.displayName(), "llm-pi-ai", List.of("providers", route)));
            }
        }
        dir.add(configurableEntry("deepseek-official", "DeepSeek", "llm-pi-ai", List.of("providers", "deepseek-official")));
        dir.add(configurableEntry("openai-compatible", "OpenAI Compatible", "llm-pi-ai", List.of("providers", "openai-compatible")));
        dir.add(configurableEntry("openai", "OpenAI", "llm-pi-ai", List.of("providers", "openai")));
        dir.add(configurableEntry("anthropic", "Anthropic", "llm-pi-ai", List.of("providers", "anthropic")));
        dir.add(configurableEntry("google", "Google", "llm-pi-ai", List.of("providers", "google")));
        return dir;
    }

    private static Map<String, Object> configurableEntry(String provider, String displayName, String settingsNs, List<String> settingsPath) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("displayName", displayName);
        m.put("settingsNs", settingsNs);
        m.put("settingsPath", settingsPath);
        m.put("declared", true);
        return m;
    }

    private static Map<String, Object> providerEntry(String route, String displayName, boolean active, boolean declared) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", route);
        m.put("displayName", displayName);
        m.put("settingsNs", "llm-pi-ai");
        m.put("settingsPath", List.of("providers", route));
        m.put("active", active);
        m.put("declared", declared);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> llmPiAiMutate(Map<String, Object> p) {
        ModelProfileStore s = storeOrNone();
        if (s == null) return llmPiAiNamespace();
        // 以当前 namespace 值为基线，叠加本次 ops（整段 profile 或 providers.<route>.<field> 均覆盖），
        // 再按路由同步到 ModelProfileStore——这样「编辑模型列表」等字段级 ops 也能持久化。
        Map<String, Object> value = new LinkedHashMap<>();
        Map<String, Object> providers = currentProvidersMap(s);
        value.put("providers", providers);
        Object opsObj = p.get("ops");
        if (opsObj instanceof List<?> ops) {
            for (Object o : ops) {
                if (!(o instanceof Map<?, ?> op)) continue;
                String opName = String.valueOf(op.get("op"));
                Object pathObj = op.get("path");
                if (!(pathObj instanceof List<?> path) || path.isEmpty()) continue;
                String[] keys = path.stream().map(String::valueOf).toArray(String[]::new);
                if ("set".equals(opName)) {
                    setNested(value, keys, op.get("value"));
                } else if ("unset".equals(opName)) {
                    unsetNested(value, keys);
                }
            }
        }
        syncProvidersToStore(s, providers);
        pushRemoteEvent("settings/document-updated", "llm-pi-ai", 1);
        pushRemoteEvent("llm/adapters-updated");
        return namespaceView("llm-pi-ai", value, llmPiAiSchema(), Map.of(), value);
    }

    private Map<String, Object> currentProvidersMap(ModelProfileStore s) {
        ensureRoutes();
        Map<String, Object> providers = new LinkedHashMap<>();
        for (ModelProfile p : s.profiles()) {
            String route = p.route();
            if (route == null || route.isBlank()) continue;
            providers.put(route, profileView(route, p));
        }
        return providers;
    }

    @SuppressWarnings("unchecked")
    private void syncProvidersToStore(ModelProfileStore s, Map<String, Object> providers) {
        for (String route : new ArrayList<>(routeToProfileId.keySet())) {
            if (!providers.containsKey(route)) {
                String id = routeToProfileId.remove(route);
                if (id != null) s.delete(id);
            }
        }
        for (var e : providers.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?>)) continue;
            upsertProfileFromValue(s, e.getKey(), (Map<String, Object>) e.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void upsertProfileFromValue(ModelProfileStore s, String route, Map<String, Object> prof) {
        String baseURL = strOf(prof.get("baseURL"));
        String displayName = strOf(prof.get("displayName"));
        if (displayName.isEmpty()) displayName = route;
        List<Map<String, Object>> models = extractModels(prof.get("models"));
        String model = models.isEmpty() ? "" : strOf(models.get(0).get("id"));
        String existingId = profileIdForRoute(route);
        String apiKey = "";
        if (existingId != null) {
            ModelProfile cur = s.profiles().stream()
                    .filter(x -> x.id().equals(existingId)).findFirst().orElse(null);
            if (cur != null) apiKey = cur.apiKey();
        }
        ModelProfile saved = s.upsert(new ModelProfile(existingId, displayName, apiKey, baseURL, model, models, route));
        routeToProfileId.put(route, saved.id());
        s.setActive(saved.id());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractModels(Object modelsObj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (modelsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> mm) out.add(new LinkedHashMap<>((Map<String, Object>) mm));
            }
        }
        return out;
    }

    private static String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> credentialDescribe(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        Object refsObj = p.get("refs");
        Map<String, Object> out = new LinkedHashMap<>();
        if (refsObj instanceof List<?> refs) {
            for (Object r : refs) {
                String ref = String.valueOf(r);
                String route = findRouteByRef(ref);
                ModelProfile prof = route == null ? null : profileForRoute(route);
                boolean mine = route != null;
                out.put(ref, Map.of(
                        "configured", mine && prof != null && !prof.apiKey().isBlank(),
                        "source", "file",
                        "writable", mine));
            }
        }
        return Map.of("credentials", out);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> credentialSet(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        return updateCredentialKey(strOf(p.get("ref")), strOf(p.get("value")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> credentialUnset(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        return updateCredentialKey(strOf(p.get("ref")), "");
    }

    private Map<String, Object> updateCredentialKey(String ref, String apiKey) {
        ModelProfileStore s = storeOrNone();
        if (s != null) {
            String route = findRouteByRef(ref);
            if (route != null) {
                String id = profileIdForRoute(route);
                if (id != null) {
                    ModelProfile cur = s.profiles().stream()
                            .filter(x -> x.id().equals(id)).findFirst().orElse(null);
                    if (cur != null) {
                        s.update(id, cur.displayName(), apiKey, cur.baseUrl(), cur.model());
                        pushRemoteEvent("credentials/reference-updated", ref);
                    }
                }
            }
        }
        return Map.of();
    }

    private String findRouteByRef(String ref) {
        if (ref.equals(deriveKeyRef("openai-compatible"))) return "openai-compatible";
        for (String route : routeToProfileId.keySet()) {
            if (ref.equals(deriveKeyRef(route))) return route;
        }
        return null;
    }

    /** workspace.archiveSession({sessionId})：加入归档集，推 host/archived-sessions-changed，回告全集。 */
    private Map<String, Object> workspaceArchiveSession(Object payload) {
        String sid = strField(payload, "sessionId");
        List<String> archived = sid.isEmpty() ? workspaces.archivedSessionIds() : workspaces.archiveSession(sid);
        remoteMux.broadcastWorkspaceFrame(Map.of("type", "archived", "archivedSessionIds", archived));
        return Map.of("archivedSessionIds", archived);
    }

    private Map<String, Object> workspaceDelete(Object payload) {
        String id = strField(payload, "workspaceId");
        workspaces.delete(id);
        remoteMux.broadcastWorkspaceFrame(Map.of("type", "remove", "workspaceId", id));
        return Map.of("deleted", true);
    }

    private Map<String, Object> workspaceRename(Object payload) {
        String id = strField(payload, "workspaceId");
        String title = strField(payload, "title");
        Map<String, Object> result = workspaces.rename(id, title);
        if (result != null) remoteMux.broadcastWorkspaceFrame(Map.of("type", "upsert", "workspace", result));
        return Map.of("workspace", result != null ? result : Map.of());
    }

    private Map<String, Object> workspaceInsertBefore() {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> w : workspaces.list()) ids.add(String.valueOf(w.get("workspaceId")));
        return Map.of("workspaceIds", ids);
    }

    private Map<String, Object> workspaceInsertSessionBefore(Object payload) {
        Map<String, Object> wv = workspaces.view(strField(payload, "workspaceId"));
        return Map.of("workspace", wv != null ? wv : Map.of());
    }

    /** 默认工作区（当前目录），sessionIds 取自 dsh-java 真实活跃会话，使侧边栏列出可点击的历史会话。 */
    private Map<String, Object> defaultWorkspace() {
        String cwd = System.getProperty("user.dir");
        String now = java.time.Instant.now().toString();
        List<String> sids = new ArrayList<>();
        try {
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
            for (SessionId id : sessions.list()) sids.add(id.value());
        } catch (Exception ignored) { /* 桥接未就绪时空 */ }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("workspaceId", "ws-default");
        w.put("path", cwd);
        w.put("title", new java.io.File(cwd).getName());
        w.put("sessionIds", sids);
        w.put("createdAt", now);
        w.put("updatedAt", now);
        return w;
    }

    private String currentModelName() {
        try {
            return holder.context().get(com.deepseek.dsh.llm.config.ModelProfileStore.class)
                    .map(s -> s.profiles().stream().filter(p -> p.id().equals(s.activeId())).findFirst()
                            .map(p -> p.model()).orElse("glm-5.2"))
                    .orElse("glm-5.2");
        } catch (Exception e) { return "glm-5.2"; }
    }

    // ---- agent presets ----

    private Map<String, Object> agentPresetList() {
        SettingsService ss = settingsService();
        String currentDefault = (ss != null)
                ? ss.get("agent-presets", "default").orElse(defaultPreset)
                : defaultPreset;
        List<Map<String, Object>> presets = new ArrayList<>();
        for (String[] s : SYSTEM_PRESETS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", s[0]);
            entry.put("trust", "system");
            entry.put("isDefault", currentDefault.equals(s[0]));
            entry.put("name", s[1]);
            entry.put("description", s[2]);
            presets.add(entry);
        }
        java.io.File dir = presetsDir();
        java.io.File[] files = dir.listFiles(f -> f.getName().endsWith(".yml"));
        if (files != null) {
            for (java.io.File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("trust", "user");
                entry.put("isDefault", false);
                entry.put("name", id);
                presets.add(entry);
            }
        }
        return Map.of("presets", presets, "authorable", true, "hasDocument", true);
    }

    private Map<String, Object> agentPresetRead(Object payload) {
        String id = strField(payload, "agentPreset");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentPreset", id);
        result.put("trust", isSystemPreset(id) ? "system" : "user");
        result.put("content", presetComposition(id));
        return result;
    }

    private String agentPresetSelect(Object payload) {
        String id = strField(payload, "agentPreset");
        defaultPreset = id;
        try {
            holder.agent().setSystemPrompt(presetSystemPrompt(id));
        } catch (Exception e) {
            log.warn("switch preset system prompt failed: {}", e.toString());
        }
        // 前端 seat-store apply() 把 result.value 直接作为 current（string），
        // 与原版 agent-presets select 返回 Promise<string>（preset.id）一致。
        // 此前返回 {agentPreset:id} 对象 → current 变对象 → React 渲染对象报错
        // → chip 被 error boundary 卸载，表现为「切换模式后消失」。
        return id;
    }

    private Map<String, Object> agentPresetCopy(Object payload) {
        String from = strField(payload, "from");
        String id = strField(payload, "agentPreset");
        java.io.File dir = presetsDir();
        dir.mkdirs();
        try {
            java.nio.file.Files.writeString(dir.toPath().resolve(id + ".yml"), presetComposition(from));
        } catch (Exception e) {
            log.warn("Failed to copy preset: {}", e.toString());
        }
        return Map.of("agentPreset", id);
    }

    private Map<String, Object> agentPresetOpenDocument() {
        java.io.File dir = presetsDir();
        try {
            if (dir.isDirectory() && java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir);
                return Map.of("opened", true);
            }
        } catch (Exception ignored) { /* 无桌面环境时回退到 path */ }
        return Map.of("opened", false, "path", dir.getAbsolutePath());
    }

    private Map<String, Object> agentPresetRemove(Object payload) {
        String id = strField(payload, "agentPreset");
        if (isSystemPreset(id)) return Map.of();
        java.io.File f = new java.io.File(presetsDir(), id + ".yml");
        if (f.isFile()) f.delete();
        return Map.of();
    }

    private static java.io.File presetsDir() {
        return new java.io.File(System.getProperty("user.home"), ".dsh/presets");
    }

    private static boolean isSystemPreset(String id) {
        for (String[] s : SYSTEM_PRESETS) {
            if (s[0].equals(id)) return true;
        }
        return false;
    }

    private static String presetComposition(String id) {
        if (!isSystemPreset(id)) {
            try {
                return java.nio.file.Files.readString(presetsDir().toPath().resolve(id + ".yml"));
            } catch (Exception e) {
                return "";
            }
        }
        try (var is = ApiproxyController.class.getResourceAsStream("/presets/" + id + "/agent.cordis.yml")) {
            if (is != null) return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to read preset composition {}: {}", id, e.toString());
        }
        return "";
    }

    /** 预设对应的系统提示：系统预设用内置文本，用户预设从其 yaml 的 system_prompt 行提取，回退默认。 */
    private static String presetSystemPrompt(String id) {
        return switch (id) {
            case "minimal" -> "You are a helpful software engineer assistant.";
            case "ptc" -> "You are a coding agent powered by the {{model}} model. Your working directory is {{cwd}}.\n\nYou operate in PTC mode: write TypeScript programs against the provided SDK to compose multi-step tool operations into a single call.";
            case "cordis" -> "You are a coding agent powered by the {{model}} model. Your working directory is {{cwd}}.\n\nYou can read and modify the harness you run on. Its composition is Cordis: every capability is a plugin row in a `cordis.yml`, and an agent preset is one such file mounted for a single session.";
            default -> "You are a coding agent powered by the {{model}} model. Your working directory is {{cwd}}.";
        };
    }

    private static final String DEFAULT_SYSTEM_PROMPT = "You are a coding agent powered by the {{model}} model. Your working directory is {{cwd}}.";

    private static String strField(Object payload, String key) {
        return payload instanceof Map<?, ?> m ? strOf(m.get(key)) : "";
    }

    // ---- frame helpers ----

    /** 推一个 session/event mux 帧（event envelope = {type, seq, time, data}）。 */
    private void sendSessionEvent(String sessionId, String eventType, Map<String, Object> data) {
        sendSessionEvent(sessionId, eventType, data, true);
    }

    private void sendSessionEvent(String sessionId, String eventType, Map<String, Object> data, boolean broadcast) {
        String surfaceOp = isSurfaceMessageEvent(eventType) ? "append" : null;
        SessionLog slog;
        try {
            slog = holder.context().require(Sessions.class).getOrCreate(SessionId.of(sessionId));
        } catch (Exception e) {
            slog = null;
        }
        SessionEvent appended = null;
        if (slog != null) {
            try {
                appended = slog.append(eventType, data, surfaceOp);
                holder.context().require(Sessions.class).persist(appended);
            } catch (Exception e) {
                log.debug("sendSessionEvent append ({}): {}", eventType, e.toString());
            }
        }
        long eventSeq = appended != null ? appended.seq() : System.currentTimeMillis();
        long eventTime = appended != null ? appended.time() : System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", eventType);
        event.put("seq", eventSeq);
        event.put("time", eventTime);
        event.put("data", data);
        if (surfaceOp != null) event.put("surfaceOp", surfaceOp);
        Map<String, Object> payload = Map.of("sessionId", sessionId, "event", event);
        try {
            downlink.sendMuxFrame(uuid(), muxFrame("session/event", payload));
        } catch (Exception e) {
            log.debug("sendSessionEvent ({}): downlink disconnected: {}", eventType, e.toString());
        }
        if (broadcast) remoteMux.broadcastFollowEvent(sessionId, event);
    }


    @SuppressWarnings("unchecked")
    private static String extractTextFromContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** surface 消息事件须带 surfaceOp:'append'，否则前端 isAppendSurfaceEvent 判否、消息节点不匹配 → 不渲染（用户消息不显示的根因）。 */
    private static boolean isSurfaceMessageEvent(String type) {
        return "user/message".equals(type) || "assistant/message".equals(type) || "tool/result".equals(type);
    }

    /** 推一个 session/projection mux 帧（如 title），更新前端侧边栏的投影值。 */
    private void sendSessionProjection(String sessionId, String key, Object value) {
        long projSeq = 0;
        try {
            SessionLog sl = holder.context().require(Sessions.class).getOrCreate(SessionId.of(sessionId));
            projSeq = sl.lastSeq();
        } catch (Exception ignored) {}
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "session/projection");
        f.put("sessionId", sessionId);
        f.put("key", key);
        f.put("value", value);
        f.put("seq", projSeq);
        try {
            downlink.sendMuxFrame(uuid(), f);
        } catch (Exception e) {
            log.debug("sendSessionProjection ({}): downlink disconnected: {}", key, e.toString());
        }
        remoteMux.broadcastControlFrame(Map.of(
                "type", "projection",
                "sessionId", sessionId,
                "key", key,
                "value", value,
                "seq", projSeq));
    }

    private static Map<String, Object> muxFrame(String type, Map<String, Object> fields) {
        Map<String, Object> f = new LinkedHashMap<>(fields);
        f.put("type", type);
        return f;
    }

    private static Map<String, Object> hostFrame(String type, Map<String, Object> fields) {
        Map<String, Object> f = new LinkedHashMap<>(fields);
        f.put("type", type);
        return f;
    }

    // ---- envelope helpers ----

    private static String uuid() { return UUID.randomUUID().toString(); }

    private static String echoRpcId(Map<String, Object> request) {
        Object id = request.get("rpcId");
        return id != null ? id.toString() : UUID.randomUUID().toString();
    }

    /** 列出会话反馈项；会话未持久化（SESSION_NOT_FOUND）按空返回，避免前端「加载失败」。 */
    private static List<Map<String, Object>> safeFeedbackItems(
            com.deepseek.dsh.feedback.MessageFeedbackService feedback, String sessionId) {
        try {
            return feedback.list(SessionId.of(sessionId)).stream()
                    .map(ApiproxyController::feedbackItem).toList();
        } catch (com.deepseek.dsh.feedback.FeedbackException fe) {
            return List.of();
        }
    }

    /** 单条反馈项的 wire map（对齐 TS MessageFeedbackItem）。 */
    private static Map<String, Object> feedbackItem(com.deepseek.dsh.feedback.MessageFeedbackItem item) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("messageId", item.messageId());
        m.put("rating", item.rating().wire());
        if (item.note() != null) m.put("note", item.note());
        m.put("version", item.version());
        m.put("createdAt", item.createdAt());
        m.put("updatedAt", item.updatedAt());
        return m;
    }

    /** Java Code → TS 连字符错误码。 */
    private static String feedbackWireCode(com.deepseek.dsh.feedback.FeedbackException.Code code) {
        return switch (code) {
            case SESSION_NOT_FOUND -> "session-not-found";
            case TARGET_NOT_FOUND -> "target-not-found";
            case VERSION_CONFLICT -> "version-conflict";
            case NOTE_BLANK -> "note-blank";
            case NOTE_TOO_LARGE -> "note-too-large";
            case SERVICE_DISPOSING -> "service-disposing";
        };
    }

    private static Map<String, Object> ok(Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> err(String code, String message) {
        return Map.of("ok", false, "error", Map.of("code", code, "message", message == null ? "" : message, "details", Map.of()));
    }

    private static Map<String, Object> response(String rpcId, Map<String, Object> result) {
        return Map.of("type", "server-response", "rpcId", rpcId, "result", result);
    }
}
