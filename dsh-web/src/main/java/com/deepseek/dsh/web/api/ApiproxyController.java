package com.deepseek.dsh.web.api;

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
import com.deepseek.dsh.web.server.WorkspaceRegistry;

/**
 * apiproxy JSON-RPC 网关（对接原版 Cordis 前端）—— {@code POST /api/{method}}。
 *
 * <p>实现原 Harness apiproxy 的最小可用子集：
 * <ul>
 *   <li>{@code host.describe}：连接握手必需。</li>
 *   <li>{@code session.create}：建会话，推 {@code host/session-added} + {@code session/subscribed}。</li>
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
            {"standard", "标准", "默认 agent，配备全部工具"},
            {"code", "代码", "专注代码读写与执行"},
            {"headless", "无界面", "headless 自动化模式"},
    };

    public ApiproxyController(AgentContextHolder holder, ApiproxyDownlinkRegistry downlink, WorkspaceRegistry workspaces) {
        this.holder = holder;
        this.downlink = downlink;
        this.workspaces = workspaces;
    }

    @PostMapping("/{method:^(?!events\\.).[A-Za-z0-9.]+$}")
    public Map<String, Object> dispatch(@PathVariable String method, @RequestBody Map<String, Object> request) {        String rpcId = echoRpcId(request);
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
                case "session.models" -> response(rpcId, ok(sessionModels()));
                case "session.selectModel" -> response(rpcId, ok(sessionSelectModel(payload)));
                case "settings.describe" -> response(rpcId, ok(settingsDescribe()));
                case "settings.openDocument" -> response(rpcId, ok(openSettingsDocument()));
                case "settings.mutate", "settings.update", "settings.replace" -> response(rpcId, ok(settingsWrite(payload)));
                case "llm.providers" -> response(rpcId, ok(llmProviders()));
                case "llm.models" -> response(rpcId, ok(Map.of("groups", List.of(), "failures", List.of())));
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
        Object payload = request.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        return handleMessageFeedback(method, p, rpcId);
    }

    /** messageFeedback 处理（dispatch 点号 + REST 斜杠共用）：接真实 MessageFeedbackService。 */
    private Map<String, Object> handleMessageFeedback(String method, Map<String, Object> p, String rpcId) {
        var feedbackOpt = holder.context().get(com.deepseek.dsh.feedback.MessageFeedbackService.class);
        if (feedbackOpt.isEmpty()) return response(rpcId, err("internal", "message feedback service not registered"));
        var feedback = feedbackOpt.get();
        try {
            return switch (method) {
                case "list" -> response(rpcId, ok(Map.of("items",
                        safeFeedbackItems(feedback, String.valueOf(p.getOrDefault("sessionId", ""))))));
                case "put" -> {
                    var item = feedback.put(
                            SessionId.of(String.valueOf(p.getOrDefault("sessionId", ""))),
                            String.valueOf(p.getOrDefault("messageId", "")),
                            com.deepseek.dsh.feedback.FeedbackRating.of(String.valueOf(p.getOrDefault("rating", ""))),
                            p.get("note") == null ? null : String.valueOf(p.get("note")),
                            p.get("ifVersion") == null ? null : String.valueOf(p.get("ifVersion")));
                    yield response(rpcId, ok(feedbackItem(item)));
                }
                case "delete" -> {
                    feedback.delete(
                            SessionId.of(String.valueOf(p.getOrDefault("sessionId", ""))),
                            String.valueOf(p.getOrDefault("messageId", "")),
                            p.get("ifVersion") == null ? null : String.valueOf(p.get("ifVersion")));
                    yield response(rpcId, ok(Map.of("absent", true)));
                }
                default -> response(rpcId, err("internal", "unknown messageFeedback method: " + method));
            };
        } catch (com.deepseek.dsh.feedback.FeedbackException fe) {
            return response(rpcId, err(feedbackWireCode(fe.code()), fe.getMessage()));
        } catch (RuntimeException re) {
            return response(rpcId, err("internal", re.getMessage()));
        }
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
        v.put("version", "0.1.1-rc.2");
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
        String cwd = p.get("cwd") != null ? String.valueOf(p.get("cwd")) : System.getProperty("user.dir");
        String workspaceId = p.get("workspaceId") != null ? String.valueOf(p.get("workspaceId")) : null;
        // 推 host/session-added + 关联工作区 + session/subscribed，使前端进入该会话
        downlink.sendHostFrame(uuid(), hostFrame("host/session-added",
                Map.of("sessionId", sid.value(), "blank", true, "cwd", cwd)));
        if (workspaceId != null) {
            Map<String, Object> wv = workspaces.attachSession(workspaceId, sid.value());
            if (wv != null) downlink.sendHostFrame(uuid(), hostFrame("host/workspace-changed", Map.of("workspace", wv)));
        }
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
        downlink.sendHostFrame(uuid(), hostFrame("host/workspace-changed",
                Map.of("workspace", result.get("workspace"))));
        return result;
    }

    private Map<String, Object> sessionList() {
        Context ctx = holder.context();
        Sessions sessions = ctx.require(Sessions.class);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionId id : sessions.list()) {
            var opt = sessions.get(id);
            if (opt.isEmpty()) continue;
            SessionLog sl = opt.get();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sessionId", id.value());
            s.put("title", customTitles.getOrDefault(id.value(), generateTitle(sl)));
            s.put("updatedAt", System.currentTimeMillis());
            s.put("running", false);
            s.put("blank", sl.size() == 0);
            s.put("cwd", System.getProperty("user.dir"));
            items.add(s);
        }
        return Map.of("items", items);
    }

    /** 会话标题：取首条用户消息前 40 字符（无 LLM，对应 BasicSessionTitleProvider 策略）。 */
    private static String generateTitle(SessionLog sl) {
        try {
            for (var m : sl.deriveMessages().messages()) {
                if (m.role() != null && "USER".equals(m.role().name())) {
                    String t = m.content() == null ? "" : m.content().trim();
                    if (!t.isEmpty()) return t.length() > 40 ? t.substring(0, 40) + "…" : t;
                    break;
                }
            }
        } catch (Exception ignored) { /* 日志未就绪时回退 */ }
        return "新会话";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionRename(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sid = strOf(p.get("sessionId"));
        String title = strOf(p.get("title"));
        if (!sid.isEmpty() && !title.isEmpty()) customTitles.put(sid, title);
        return Map.of("title", title, "seq", 0);
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
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog parent = sessions.get(SessionId.of(parentSid)).orElse(null);
            if (parent == null) return Map.of("sessionId", UUID.randomUUID().toString());
            SessionLog child = sessions.create();
            for (SessionEvent e : parent.snapshot()) {
                sessions.persist(child.append(e.type(), e.payload()));
            }
            downlink.sendHostFrame(uuid(), hostFrame("host/session-added",
                    Map.of("sessionId", child.sessionId().value(), "blank", child.size() == 0,
                            "cwd", System.getProperty("user.dir"))));
            return Map.of("sessionId", child.sessionId().value());
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
        // 列出 active profile 的 models 数组里全部模型（用户在设置里添加的模型也出现在选择器）
        List<Map<String, Object>> models = new ArrayList<>();
        ModelProfile ap = activeProfile();
        if (ap != null && ap.models() != null) {
            for (Map<String, Object> mm : ap.models()) {
                Object idObj = mm.getOrDefault("id", mm.get("name"));
                String id = idObj == null ? "" : String.valueOf(idObj);
                if (id.isEmpty() || "null".equals(id)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                Object nameObj = mm.get("name");
                entry.put("name", nameObj == null || String.valueOf(nameObj).isEmpty() ? id : String.valueOf(nameObj));
                models.add(entry);
            }
        }
        if (models.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", model); m.put("name", model);
            models.add(m);
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "openai-compatible"); group.put("name", "OpenAI Compatible");
        group.put("models", models);
        return Map.of("current", sel, "routable", true, "groups", List.of(group), "failures", List.of());
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
        return Map.of("selected", Map.of("provider", "openai-compatible", "model", model));
    }

    // ---- session.prompt → agent → frames ----

    private Map<String, Object> sessionPrompt(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sessionId = String.valueOf(p.getOrDefault("sessionId", UUID.randomUUID().toString()));
        String text = extractPromptText(p);
        // turn 取会话日志中已有用户消息数（0 基）：跨多轮递增且与 history 重放一致，避免 ConversationNodeAssembler 节点 id（turn:step）冲突。
        int turn = nextTurn(sessionId);
        String userMsgId = "u-" + UUID.randomUUID().toString().substring(0, 8);

        // turn/start + user/message；step 由观察者按「每次模型回复」递增（step/start↔step/end 包住每个 step）。
        sendSessionEvent(sessionId, "turn/start", Map.of("turn", turn));
        sendSessionEvent(sessionId, "user/message", Map.of(
                "id", userMsgId,
                "content", List.of(Map.of("type", "text", "text", text)),
                "source", Map.of("kind", "user")));
        // 推送标题投影（首条用户消息前 40 字符），使侧边栏显示问答标题而非 cwd basename。
        sendSessionProjection(sessionId, "title", text.length() > 40 ? text.substring(0, 40) + "…" : text);
        downlink.sendHostFrame(uuid(), hostFrame("host/session-status",
                Map.of("sessionId", sessionId, "running", true)));

        // 未分组会话自动按「年-月-日-时」分配工作区，便于按时间批量查询
        if (workspaces.findSessionWorkspace(sessionId) == null) {
            String dateHour = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH"));
            Map<String, Object> wsResult = workspaces.ensure(dateHour);
            if (wsResult.get("workspace") instanceof Map<?, ?> wv) {
                String wsId = String.valueOf(wv.get("workspaceId"));
                Map<String, Object> attached = workspaces.attachSession(wsId, sessionId);
                if (attached != null) {
                    downlink.sendHostFrame(uuid(), hostFrame("host/workspace-changed", Map.of("workspace", attached)));
                }
            }
        }

        Thread turnThread = Thread.startVirtualThread(() -> runTurn(sessionId, text, turn));
        runningTurns.put(sessionId, turnThread);
        return Map.of("accepted", true);
    }

    private int nextTurn(String sessionId) {
        try {
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
            SessionLog slog = sessions.get(SessionId.of(sessionId)).orElse(null);
            if (slog != null) {
                int count = 0;
                for (var m : slog.deriveMessages().messages()) {
                    if (m.role() != null && "USER".equals(m.role().name())) count++;
                }
                return count;
            }
        } catch (Exception ignored) { /* 桥接未就绪时退回 0 */ }
        return 0;
    }

    private void runTurn(String sessionId, String text, int turn) {
        String model = sessionModelSelection.getOrDefault(sessionId, currentModelName());
        // 当前 step 索引：-1 表示尚无模型回复。每次 onAssistantMessage 先收尾上一 step（step/end），
        // 再自增并开新 step（step/start），保证 step/start↔step/end 严格嵌套、节点 id（turn:step）唯一，避免「more than one start Match」。
        int[] step = {-1};
        boolean cancelled = false;
        try {
            Context ctx = holder.context();
            Agent agent = holder.agent();
            agent.runObserved(SessionId.of(sessionId), ScopeKey.random(), ctx, text, new com.deepseek.dsh.agent.TurnObserver() {
                @Override public void onAssistantMessage(String content, String reasoning) {
                    if ((content == null || content.isEmpty()) && (reasoning == null || reasoning.isEmpty())) return;
                    if (step[0] >= 0) sendSessionEvent(sessionId, "step/end", Map.of("turn", turn, "step", step[0]));
                    step[0]++;
                    sendSessionEvent(sessionId, "step/start", Map.of("turn", turn, "step", step[0]));
                    if (reasoning != null && !reasoning.isEmpty()) {
                        sendSessionEvent(sessionId, "assistant/chunk", Map.of(
                                "chunk", Map.of("type", "reasoning-delta", "index", 0, "text", reasoning),
                                "turn", turn, "step", step[0]));
                    }
                    if (content != null && !content.isEmpty()) {
                        sendSessionEvent(sessionId, "assistant/chunk", Map.of(
                                "chunk", Map.of("type", "text-delta", "index", 0, "text", content),
                                "turn", turn, "step", step[0]));
                        sendSessionEvent(sessionId, "assistant/message", Map.of(
                                "message", Map.of(
                                        "id", "a-" + uuid().substring(0, 8),
                                        "content", List.of(textPart(content)),
                                        "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", model)),
                                "turn", turn, "step", step[0]));
                    }
                }
                @Override public void onToolCall(String callId, String name, String argumentsJson) {
                    sendSessionEvent(sessionId, "tool/call", Map.of(
                            "callId", callId, "name", name, "arguments", argumentsJson,
                            "turn", turn, "step", step[0]));
                }
                @Override public void onToolResult(String callId, String resultText) {
                    Map<String, Object> toolResultBlock = new LinkedHashMap<>();
                    toolResultBlock.put("type", "tool-result");
                    toolResultBlock.put("toolCallId", callId);
                    toolResultBlock.put("content", List.of(textPart(resultText)));
                    toolResultBlock.put("isError", false);
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("content", List.of(toolResultBlock));
                    msg.put("source", Map.of("callId", callId));
                    sendSessionEvent(sessionId, "tool/result", Map.of(
                            "message", msg,
                            "turn", turn, "step", step[0]));
                }
            });
        } catch (Exception e) {
            log.warn("agent turn {}: {}", Thread.currentThread().isInterrupted() ? "cancelled" : "failed", e.toString());
            // 把 agent/模型失败的原因作为 assistant 消息推入聊天流，让用户在聊天框看到具体错误
            // （如 LLM stream error 403 / Model.AccessDenied），而不是静默「无回复」。
            if (!cancelledSessions.contains(sessionId)) {
                if (step[0] < 0) { step[0] = 0; sendSessionEvent(sessionId, "step/start", Map.of("turn", turn, "step", step[0])); }
                String detail = e.getMessage();
                Throwable cause = e.getCause();
                if (cause != null && cause.getMessage() != null) detail += " — " + cause.getMessage();
                String errMsg = "模型调用失败：" + detail;
                sendSessionEvent(sessionId, "assistant/chunk", Map.of(
                        "chunk", Map.of("type", "text-delta", "index", 0, "text", errMsg),
                        "turn", turn, "step", step[0]));
                sendSessionEvent(sessionId, "assistant/message", Map.of(
                        "message", Map.of(
                                "id", "a-err-" + uuid().substring(0, 8),
                                "content", List.of(textPart(errMsg)),
                                "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", model)),
                        "turn", turn, "step", step[0]));
                // 记入 session log，使 session.history 回放也含该错误（UI 刷新/重连后仍可见，不只走实时 mux）
                try {
                    SessionLog slog = holder.context().require(Sessions.class).get(SessionId.of(sessionId)).orElse(null);
                    if (slog != null) slog.append(SessionEvent.Type.ASSISTANT_MESSAGE, SessionEvent.Payload.text(errMsg));
                } catch (Exception ignored) { /* session log 不可用时仅实时 mux 推送 */ }
            }
        } finally {
            runningTurns.remove(sessionId);
        }
        cancelled = cancelledSessions.remove(sessionId);
        if (step[0] >= 0) sendSessionEvent(sessionId, "step/end", Map.of("turn", turn, "step", step[0]));
        sendSessionEvent(sessionId, "turn/end", Map.of("turn", turn,
                "reason", Map.of("kind", cancelled ? "aborted" : "complete")));
        downlink.sendHostFrame(uuid(), hostFrame("host/session-status",
                Map.of("sessionId", sessionId, "running", false)));
    }

    /** session.history：把 dsh-java 投影消息映射为 harness 事件信封（user/message + turn/assistant-message），供 UI 重放历史。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionHistory(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sessionId = String.valueOf(p.getOrDefault("sessionId", ""));
        List<Map<String, Object>> events = new ArrayList<>();
        long t = System.currentTimeMillis();
        long[] idc = {0}; // 消息 id 局部计数；事件 seq 用全局 mux seq（与实时流同源）
        int[] turn = {0};
        try {
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
                SessionLog slog = sessions.get(SessionId.of(sessionId)).orElse(null);
                if (slog != null) {
                    int[] step = {0};
                    boolean[] turnOpen = {false};
                    // 遍历原始事件重放完整轨迹：用户/助手消息 + 思维链(reasoning) + 工具调用/结果，
                    // 使刷新页面后思维链与工具调用仍可见（与实时流帧同构）。
                    for (SessionEvent e : slog.events()) {
                        switch (e.type()) {
                            case USER_MESSAGE -> {
                                if (turnOpen[0]) {
                                    events.add(historyEntry(envelope("step/end", seq.getAndIncrement(), t++, Map.of("turn", turn[0] - 1, "step", step[0]))));
                                    events.add(historyEntry(envelope("turn/end", seq.getAndIncrement(), t++, Map.of("turn", turn[0] - 1, "reason", Map.of("kind", "complete")))));
                                }
                                int tn = turn[0]++;
                                step[0] = 0;
                                turnOpen[0] = true;
                                events.add(historyEntry(envelope("turn/start", seq.getAndIncrement(), t++, Map.of("turn", tn))));
                                events.add(historyEntry(envelope("step/start", seq.getAndIncrement(), t++, Map.of("turn", tn, "step", step[0]))));
                                String uc = e.payload().text() == null ? "" : e.payload().text();
                                events.add(historyEntry(envelope("user/message", seq.getAndIncrement(), t++, Map.of(
                                        "id", "u-" + idc[0]++, "content", List.of(textPart(uc)), "source", Map.of("kind", "user")))));
                            }
                            case ASSISTANT_MESSAGE -> {
                                if (turnOpen[0]) {
                                    int tn = turn[0] - 1;
                                    String content = e.payload().text() == null ? "" : e.payload().text();
                                    String reasoning = e.payload().reasoning();
                                    if (reasoning != null && !reasoning.isBlank()) {
                                        events.add(historyEntry(envelope("assistant/chunk", seq.getAndIncrement(), t++, Map.of(
                                                "chunk", Map.of("type", "reasoning-delta", "index", 0, "text", reasoning),
                                                "turn", tn, "step", step[0]))));
                                    }
                                    if (!content.isBlank()) {
                                        events.add(historyEntry(envelope("assistant/message", seq.getAndIncrement(), t++, Map.of(
                                                "message", Map.of(
                                                        "id", "a-" + idc[0]++,
                                                        "content", List.of(textPart(content)),
                                                        "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", currentModelName())),
                                                "turn", tn, "step", step[0]))));
                                    }
                                }
                            }
                            case TOOL_CALL -> {
                                if (turnOpen[0]) {
                                    int tn = turn[0] - 1;
                                    events.add(historyEntry(envelope("tool/call", seq.getAndIncrement(), t++, Map.of(
                                            "callId", e.payload().toolCallId() == null ? "" : e.payload().toolCallId(),
                                            "name", e.payload().toolName() == null ? "" : e.payload().toolName(),
                                            "arguments", toolArgsJson(e.payload().structured()),
                                            "turn", tn, "step", step[0]))));
                                }
                            }
                            case TOOL_RESULT -> {
                                if (turnOpen[0]) {
                                    int tn = turn[0] - 1;
                                    Map<String, Object> toolResultBlock = new LinkedHashMap<>();
                                    toolResultBlock.put("type", "tool-result");
                                    toolResultBlock.put("toolCallId", e.payload().toolCallId() == null ? "" : e.payload().toolCallId());
                                    toolResultBlock.put("content", List.of(textPart(e.payload().text())));
                                    toolResultBlock.put("isError", false);
                                    Map<String, Object> msg = new LinkedHashMap<>();
                                    msg.put("content", List.of(toolResultBlock));
                                    msg.put("source", Map.of("callId", e.payload().toolCallId() == null ? "" : e.payload().toolCallId()));
                                    events.add(historyEntry(envelope("tool/result", seq.getAndIncrement(), t++, Map.of(
                                            "message", msg, "turn", tn, "step", step[0]))));
                                }
                            }
                            default -> {}
                        }
                    }
                    if (turnOpen[0]) {
                        events.add(historyEntry(envelope("step/end", seq.getAndIncrement(), t++, Map.of("turn", turn[0] - 1, "step", step[0]))));
                        events.add(historyEntry(envelope("turn/end", seq.getAndIncrement(), t++, Map.of("turn", turn[0] - 1, "reason", Map.of("kind", "complete")))));
                    }
                }
        } catch (Exception e) {
            log.warn("session.history failed: {}", e.toString());
        }
        // projections block：标题取首条 user/message 的文本，asOfSeq 取末事件 seq。
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
        return Map.of(
                "writable", true,
                "hasDocument", true,
                "namespaces", List.of(llmPiAiNamespace(), uiOnboardingNamespace(), agentPresetsNamespace()));
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
        s.set(ns, field, String.valueOf(val));
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

    private Map<String, Object> llmProviders() {
        ensureRoutes();
        ModelProfileStore s = storeOrNone();
        String activeId = s == null ? null : s.activeId();
        List<Map<String, Object>> providers = new ArrayList<>();
        if (s != null) {
            for (ModelProfile p : s.profiles()) {
                String route = p.route();
                if (route == null || route.isBlank()) continue;
                providers.add(providerEntry(route, p.displayName(), p.id().equals(activeId), true));
            }
        }
        return Map.of("providers", providers);
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
        downlink.sendHostFrame(uuid(), hostFrame("host/archived-sessions-changed", Map.of("archivedSessionIds", archived)));
        return Map.of("archivedSessionIds", archived);
    }

    private Map<String, Object> workspaceDelete(Object payload) {
        String id = strField(payload, "workspaceId");
        workspaces.delete(id);
        downlink.sendHostFrame(uuid(), hostFrame("host/workspace-removed", Map.of("workspaceId", id)));
        return Map.of("deleted", true);
    }

    private Map<String, Object> workspaceRename(Object payload) {
        String id = strField(payload, "workspaceId");
        String title = strField(payload, "title");
        Map<String, Object> wv = workspaces.rename(id, title);
        if (wv != null) downlink.sendHostFrame(uuid(), hostFrame("host/workspace-changed", Map.of("workspace", wv)));
        return Map.of("workspace", wv != null ? wv : Map.of());
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
            presets.add(Map.of(
                    "id", s[0], "trust", "system", "isDefault", currentDefault.equals(s[0]),
                    "name", s[1], "description", s[2]));
        }
        java.io.File dir = presetsDir();
        java.io.File[] files = dir.listFiles(f -> f.getName().endsWith(".yml"));
        if (files != null) {
            for (java.io.File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                presets.add(Map.of("id", id, "trust", "user", "isDefault", false, "name", id));
            }
        }
        return Map.of("presets", presets, "authorable", true, "hasDocument", true);
    }

    private Map<String, Object> agentPresetRead(Object payload) {
        String id = strField(payload, "agentPreset");
        return Map.of("agentPreset", id, "trust", isSystemPreset(id) ? "system" : "user",
                "content", presetComposition(id));
    }

    /** 单 agent 架构：select 全局切换 agent 的系统提示（原版按会话重组需 per-session agent 重构；这里至少让选择的预设即时影响下一回合）。 */
    private Map<String, Object> agentPresetSelect(Object payload) {
        String id = strField(payload, "agentPreset");
        defaultPreset = id;
        try {
            holder.agent().setSystemPrompt(presetSystemPrompt(id));
        } catch (Exception e) {
            log.warn("switch preset system prompt failed: {}", e.toString());
        }
        return Map.of("agentPreset", id);
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
        return switch (id) {
            case "code" -> "# code preset\ntools: [bash, read, write, edit, glob, grep, terminal]\nsystem_prompt: 你是 DeepSeek Harness 代码助手——专注代码读写、编辑、执行与调试。\n";
            case "headless" -> "# headless preset\ntools: [bash, read, write, edit, glob, grep, job, todo_write]\nsystem_prompt: 你是 DeepSeek Harness headless 助手——执行自动化任务，无界面交互。\n";
            default -> "# standard preset\ntools: [bash, read, write, edit, glob, grep, terminal, web_search, web_fetch, job, todo_write, goal, workflow, ralph, skill]\nsystem_prompt: 你是 DeepSeek Harness，强大的软件工程助手。\n";
        };
    }

    /** 预设对应的系统提示：系统预设用内置文本，用户预设从其 yaml 的 system_prompt 行提取，回退默认。 */
    private static String presetSystemPrompt(String id) {
        if (isSystemPreset(id)) {
            return switch (id) {
                case "code" -> "你是 DeepSeek Harness 代码助手——专注代码读写、编辑、执行与调试，优先使用 bash/read/write/edit/grep/terminal 工具。";
                case "headless" -> "你是 DeepSeek Harness headless 助手——执行自动化任务，无界面交互，优先使用 bash/read/write/job/todo_write 工具。";
                default -> DEFAULT_SYSTEM_PROMPT;
            };
        }
        String yaml = presetComposition(id);
        for (String line : yaml.split("\\R")) {
            if (line.startsWith("system_prompt:")) {
                return line.substring("system_prompt:".length()).trim();
            }
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 DeepSeek Harness（dsh）—— 一个强大的软件工程助手。
            你可以使用以下工具完成编程任务：bash、terminal、read/write/edit/glob/grep、web_search/web_fetch、job、todo_write、goal、workflow/ralph、skill、team。
            请优先使用工具获取信息，给出简洁、准确的回答。面对复杂任务时可设定目标或进入计划模式。""";

    private static String strField(Object payload, String key) {
        return payload instanceof Map<?, ?> m ? strOf(m.get(key)) : "";
    }

    // ---- frame helpers ----

    /** 推一个 session/event mux 帧（event envelope = {type, seq, time, data}）。 */
    private void sendSessionEvent(String sessionId, String eventType, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", eventType);
        event.put("seq", seq.getAndIncrement());
        event.put("time", System.currentTimeMillis());
        event.put("data", data);
        if (isSurfaceMessageEvent(eventType)) event.put("surfaceOp", "append");
        downlink.sendMuxFrame(uuid(), muxFrame("session/event",
                Map.of("sessionId", sessionId, "event", event)));
    }

    /** surface 消息事件须带 surfaceOp:'append'，否则前端 isAppendSurfaceEvent 判否、消息节点不匹配 → 不渲染（用户消息不显示的根因）。 */
    private static boolean isSurfaceMessageEvent(String type) {
        return "user/message".equals(type) || "assistant/message".equals(type) || "tool/result".equals(type);
    }

    /** 推一个 session/projection mux 帧（如 title），更新前端侧边栏的投影值。 */
    private void sendSessionProjection(String sessionId, String key, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "session/projection");
        f.put("sessionId", sessionId);
        f.put("key", key);
        f.put("value", value);
        f.put("seq", seq.getAndIncrement());
        downlink.sendMuxFrame(uuid(), f);
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
