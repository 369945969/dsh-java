package com.deepseek.dsh.web.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.SessionLog;
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

    public ApiproxyController(AgentContextHolder holder, ApiproxyDownlinkRegistry downlink, WorkspaceRegistry workspaces) {
        this.holder = holder;
        this.downlink = downlink;
        this.workspaces = workspaces;
    }

    @PostMapping("/{method:^(?!events\\.).[A-Za-z0-9.]+$}")
    public Map<String, Object> dispatch(@PathVariable String method, @RequestBody Map<String, Object> request) {        String rpcId = echoRpcId(request);
        Object payload = request.get("payload");
        log.debug("apiproxy {} payload={}", method, payload);
        try {
            return switch (method) {
                case "host.describe" -> response(rpcId, ok(hostDescribe()));
                case "session.create" -> response(rpcId, ok(sessionCreate(payload)));
                case "session.list" -> response(rpcId, ok(sessionList()));
                case "session.history" -> response(rpcId, ok(sessionHistory(payload)));
                case "session.prompt" -> response(rpcId, ok(sessionPrompt(payload)));
                case "session.cancel" -> response(rpcId, ok(Map.of("accepted", true)));
                case "session.rename" -> response(rpcId, ok(Map.of("title", "session", "seq", 0)));
                case "session.models" -> response(rpcId, ok(sessionModels()));
                case "settings.mutate", "settings.update", "settings.replace" -> response(rpcId, ok(settingsWrite(payload)));
                case "workspace.create" -> response(rpcId, ok(workspaceCreate(payload)));
                case "workspace.list" -> response(rpcId, ok(Map.of("items", workspaces.list(), "archivedSessionIds", List.of())));
                case "host.listDirectory" -> response(rpcId, ok(listDirectory(payload)));
                default -> response(rpcId, ok(valueOf(method)));
            };
        } catch (RuntimeException e) {
            log.warn("apiproxy {} 失败: {}", method, e.toString());
            return response(rpcId, err("internal", e.getMessage()));
        }
    }

    /** dynamicCordisRunner/* （Cordis 工具清单/inspect）：两段路径，返回空 ok 以清 404（面板空，非致命）。 */
    @PostMapping("/dynamicCordisRunner/{method}")
    public Map<String, Object> cordisRunner(@PathVariable String method, @RequestBody Map<String, Object> request) {
        return response(echoRpcId(request), ok(Map.of()));
    }

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
                Map.of("sessionId", sid.value(), "lastSeq", slog.lastSeq())));
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
            throw new RuntimeException("workspace-invalid-path: " + path + " 不是目录");
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
            s.put("updatedAt", System.currentTimeMillis());
            s.put("running", false);
            s.put("blank", sl.size() == 0);
            s.put("cwd", System.getProperty("user.dir"));
            items.add(s);
        }
        return Map.of("items", items);
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", model); m.put("name", model);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "openai-compatible"); group.put("name", "OpenAI Compatible");
        group.put("models", List.of(m));
        return Map.of("current", sel, "routable", true, "groups", List.of(group), "failures", List.of());
    }

    // ---- session.prompt → agent → frames ----

    private Map<String, Object> sessionPrompt(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String sessionId = String.valueOf(p.getOrDefault("sessionId", UUID.randomUUID().toString()));
        String text = extractPromptText(p);
        // turn/step 必须是数值索引（runtime 的 ConversationLocationData.turn 校验 isSafeInteger）
        int turn = 0, step = 0;
        String userMsgId = "u-" + UUID.randomUUID().toString().substring(0, 8);
        String assistantMsgId = "a-" + UUID.randomUUID().toString().substring(0, 8);

        // 1) turn/start + step/start + user/message
        sendSessionEvent(sessionId, "turn/start", Map.of("turn", turn));
        sendSessionEvent(sessionId, "step/start", Map.of("turn", turn, "step", step));
        sendSessionEvent(sessionId, "user/message", Map.of(
                "id", userMsgId,
                "content", List.of(Map.of("type", "text", "text", text)),
                "source", Map.of("kind", "user")));
        downlink.sendHostFrame(uuid(), hostFrame("host/session-status",
                Map.of("sessionId", sessionId, "running", true)));

        // 2) 异步运行 agent（ReAct 循环，含工具），观察者把 assistant/tool 事件映射为 mux 帧
        Thread.startVirtualThread(() -> runTurn(sessionId, text, turn, step));
        return Map.of("accepted", true);
    }

    private void runTurn(String sessionId, String text, int turn, int step) {
        String model = currentModelName();
        try {
            Context ctx = holder.context();
            Agent agent = holder.agent();
            agent.runObserved(SessionId.of(sessionId), ScopeKey.random(), ctx, text, new com.deepseek.dsh.agent.TurnObserver() {
                @Override public void onAssistantMessage(String content) {
                    if (content == null || content.isEmpty()) return;
                    sendSessionEvent(sessionId, "assistant/chunk", Map.of(
                            "chunk", Map.of("type", "text-delta", "index", 0, "text", content),
                            "turn", turn, "step", step));
                    sendSessionEvent(sessionId, "assistant/message", Map.of(
                            "message", Map.of(
                                    "id", "a-" + uuid().substring(0, 8),
                                    "content", List.of(textPart(content)),
                                    "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", model)),
                            "turn", turn, "step", step));
                }
                @Override public void onToolCall(String callId, String name, String argumentsJson) {
                    sendSessionEvent(sessionId, "tool/call", Map.of(
                            "callId", callId, "name", name, "arguments", argumentsJson,
                            "turn", turn, "step", step));
                }
                @Override public void onToolResult(String callId, String resultText) {
                    sendSessionEvent(sessionId, "tool/result", Map.of(
                            "message", Map.of(
                                    "content", List.of(textPart(resultText)),
                                    "source", Map.of("callId", callId)),
                            "turn", turn, "step", step));
                }
            });
        } catch (Exception e) {
            log.warn("agent 回合失败: {}", e.toString());
        }
        sendSessionEvent(sessionId, "step/end", Map.of("turn", turn, "step", step));
        sendSessionEvent(sessionId, "turn/end", Map.of("turn", turn, "reason", Map.of("kind", "complete")));
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
        long[] seq = {0};
        int[] turn = {0};
        try {
            Context ctx = holder.context();
            Sessions sessions = ctx.require(Sessions.class);
                SessionLog slog = sessions.get(SessionId.of(sessionId)).orElse(null);
                if (slog != null) {
                var msgs = slog.deriveMessages().messages();
                for (int i = 0; i < msgs.size(); i++) {
                    var msg = msgs.get(i);
                    String role = msg.role() == null ? "" : msg.role().name();
                    String content = msg.content() == null ? "" : msg.content();
                    boolean isUser = "USER".equals(role);
                    boolean isAssistant = "ASSISTANT".equals(role);
                    if (!isUser && !isAssistant) continue;
                    int tn = turn[0]++;
                    // 每个回合：turn/start → step/start → user/message → assistant/message → step/end → turn/end（与实时流同构）
                    events.add(historyEntry(envelope("turn/start", seq[0]++, t++, Map.of("turn", tn))));
                    events.add(historyEntry(envelope("step/start", seq[0]++, t++, Map.of("turn", tn, "step", 0))));
                    if (isUser) {
                        events.add(historyEntry(envelope("user/message", seq[0]++, t++, Map.of(
                                "id", "u-" + seq[0], "content", List.of(textPart(content)), "source", Map.of("kind", "user")))));
                        if (i + 1 < msgs.size() && msgs.get(i + 1).role() != null
                                && "ASSISTANT".equals(msgs.get(i + 1).role().name())) {
                            i++;
                            String ac = msgs.get(i).content() == null ? "" : msgs.get(i).content();
                            events.add(historyEntry(envelope("assistant/message", seq[0]++, t++, Map.of(
                                    "message", Map.of(
                                            "id", "a-" + seq[0],
                                            "content", List.of(textPart(ac)),
                                            "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", currentModelName())),
                                    "turn", tn, "step", 0))));
                        }
                    } else {
                        events.add(historyEntry(envelope("assistant/message", seq[0]++, t++, Map.of(
                                "message", Map.of(
                                        "id", "a-" + seq[0],
                                        "content", List.of(textPart(content)),
                                        "source", Map.of("kind", "assistant", "provider", "openai-compatible", "model", currentModelName())),
                                "turn", tn, "step", 0))));
                    }
                    events.add(historyEntry(envelope("step/end", seq[0]++, t++, Map.of("turn", tn, "step", 0))));
                    events.add(historyEntry(envelope("turn/end", seq[0]++, t++, Map.of("turn", tn, "reason", Map.of("kind", "complete")))));
                }
            }
        } catch (Exception e) {
            log.warn("session.history 失败: {}", e.toString());
        }
        return Map.of("events", events, "hasMore", false);
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
        e.put("type", type); e.put("seq", seq); e.put("time", time); e.put("data", data);
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
            case "session.fork" -> Map.of("sessionId", UUID.randomUUID().toString());
            case "session.attachment" -> Map.of();
            case "session.updateQueue" -> Map.of("accepted", true);
            case "session.selectModel" -> Map.of("selected", Map.of("provider", "openai-compatible", "model", currentModelName()));
            case "workspace.list" -> Map.of("items", List.of(defaultWorkspace()), "archivedSessionIds", List.of());
            case "workspace.create" -> Map.of("workspace", Map.of("workspaceId", UUID.randomUUID().toString(), "title", "workspace", "sessionIds", List.of()), "created", true);
            case "workspace.rename", "workspace.delete", "workspace.insertBefore", "workspace.insertSessionBefore", "workspace.archiveSession" -> Map.of();
            case "settings.describe" -> settingsDescribe();
            case "settings.openDocument", "settings.update", "settings.replace", "settings.mutate" -> Map.of();
            case "llm.providers" -> Map.of("providers", List.of(Map.of(
                    "provider", "openai-compatible", "displayName", "OpenAI Compatible",
                    "settingsNs", "llm", "settingsPath", List.of("llm"), "active", true)));
            case "llm.models", "llm.discoverModels" -> Map.of("models", List.of(), "failures", List.of());
            case "agentPreset.list" -> Map.of("presets", List.of());
            case "agentPreset.select" -> Map.of();
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
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("writable", false);
        v.put("hasDocument", false);
        v.put("namespaces", List.of());
        v.put("secrets", List.of());
        return v;
    }

    /** settings.mutate/update/replace：应用 patch/ops/section 构造 NamespaceValue 并回显，使 welcome 确认持久化、通知关闭。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> settingsWrite(Object payload) {
        Map<String, Object> p = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        String ns = String.valueOf(p.getOrDefault("ns", "ui-onboarding"));
        Map<String, Object> value = new LinkedHashMap<>();
        // settings.update: {patch:{field:value}}
        Object patch = p.get("patch");
        if (patch instanceof Map<?, ?> pm) for (Object k : pm.keySet()) value.put(String.valueOf(k), pm.get(k));
        // settings.replace: {section:{...}}
        Object section = p.get("section");
        if (section instanceof Map<?, ?> sm) for (Object k : sm.keySet()) value.put(String.valueOf(k), sm.get(k));
        // settings.mutate: {ops:[{op:'set'|'unset',path:[...],value}]}
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
                } else if ("unset".equals(opName)) {
                    unsetNested(value, keys);
                }
            }
        }
        return namespaceView(ns, value);
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
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ns", ns);
        v.put("schema", Map.of());
        v.put("value", value);
        v.put("applies", "live");
        v.put("secrets", List.of());
        v.put("revision", 1);
        return v;
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

    // ---- frame helpers ----

    /** 推一个 session/event mux 帧（event envelope = {type, seq, time, data}）。 */
    private void sendSessionEvent(String sessionId, String eventType, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", eventType);
        event.put("seq", seq.getAndIncrement());
        event.put("time", System.currentTimeMillis());
        event.put("data", data);
        downlink.sendMuxFrame(uuid(), muxFrame("session/event",
                Map.of("sessionId", sessionId, "event", event)));
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

    private static Map<String, Object> ok(Object value) { return Map.of("ok", true, "value", value); }

    private static Map<String, Object> err(String code, String message) {
        return Map.of("ok", false, "error", Map.of("code", code, "message", message == null ? "" : message, "details", Map.of()));
    }

    private static Map<String, Object> response(String rpcId, Map<String, Object> result) {
        return Map.of("type", "server-response", "rpcId", rpcId, "result", result);
    }
}
