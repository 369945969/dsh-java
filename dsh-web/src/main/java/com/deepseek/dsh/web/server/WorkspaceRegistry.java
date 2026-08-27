package com.deepseek.dsh.web.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工作区注册表 —— 对应原 Harness 的 {@code ctx.workspaceRegistry}。
 *
 * <p>工作区 = 一个目录路径 + 其下会话集合。{@code workspace.create({path})} 采纳一个真实目录，
 * 路径已存在则复用（created:false），否则新建。{@code session.create} 把会话挂到工作区。
 * {@code workspace.archiveSession} 把会话加入全局归档集（隐藏出分组，不删日志）。
 *
 * <p>持久化到 {@code ~/.dsh/workspaces.json}，跨后端重启存活（解决刷新后会话变未分组）。
 *
 * <p>设计模式：注册表（按路径/工作区 id 索引的工作区记录）+ 仓储（文件持久化）。
 */
@Component
public class WorkspaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Workspace(String id, String path, String title, List<String> sessionIds, String createdAt, String updatedAt) {}

    private final Map<String, Workspace> byId = new ConcurrentHashMap<>();
    private final Map<String, String> pathToId = new ConcurrentHashMap<>();
    private final java.util.Set<String> archived = ConcurrentHashMap.newKeySet();
    private final java.nio.file.Path storeFile;

    public WorkspaceRegistry() {
        this.storeFile = java.nio.file.Path.of(System.getProperty("user.home"), ".dsh", "workspaces.json");
        load();
    }

    /** 按路径采纳工作区：已存在则复用，否则新建。返回 {workspace, created}。 */
    public Map<String, Object> ensure(String path) {
        synchronized (pathToId) {
            String existing = pathToId.get(path);
            if (existing != null) {
                return Map.of("workspace", view(byId.get(existing)), "created", false);
            }
            String id = "ws-" + UUID.randomUUID().toString().substring(0, 8);
            String now = java.time.Instant.now().toString();
            String title = new java.io.File(path).getName();
            if (title == null || title.isEmpty()) title = path;
            Workspace w = new Workspace(id, path, title, new ArrayList<>(), now, now);
            byId.put(id, w);
            pathToId.put(path, id);
            save();
            return Map.of("workspace", view(w), "created", true);
        }
    }

    public Map<String, Object> view(String workspaceId) {
        Workspace w = byId.get(workspaceId);
        return w == null ? null : view(w);
    }

    /** 把会话挂到工作区，返回更新后的工作区视图。 */
    public Map<String, Object> attachSession(String workspaceId, String sessionId) {
        Workspace w = byId.get(workspaceId);
        if (w == null) return null;
        if (!w.sessionIds().contains(sessionId)) w.sessionIds().add(sessionId);
        Workspace updated = new Workspace(w.id(), w.path(), w.title(), w.sessionIds(), w.createdAt(), java.time.Instant.now().toString());
        byId.put(workspaceId, updated);
        save();
        return view(updated);
    }

    /** 重命名工作区，返回更新后的视图；不存在返回 null。 */
    public Map<String, Object> rename(String workspaceId, String title) {
        Workspace w = byId.get(workspaceId);
        if (w == null) return null;
        Workspace updated = new Workspace(w.id(), w.path(), title, w.sessionIds(), w.createdAt(), java.time.Instant.now().toString());
        byId.put(workspaceId, updated);
        save();
        return view(updated);
    }

    /** 删除工作区注册（目录/会话日志保留，会话变未分组）。成功返回 true。 */
    public boolean delete(String workspaceId) {
        Workspace w = byId.remove(workspaceId);
        if (w == null) return false;
        pathToId.remove(w.path());
        save();
        return true;
    }

    /** 查找会话所属的工作区 ID；未归属返回 null。 */
    public String findSessionWorkspace(String sessionId) {
        for (Workspace w : byId.values()) {
            if (w.sessionIds().contains(sessionId)) return w.id();
        }
        return null;
    }

    /** 全部工作区视图。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Workspace w : byId.values()) items.add(view(w));
        return items;
    }

    /** 归档会话集（全局，隐藏出分组表面）。 */
    public List<String> archivedSessionIds() {
        return List.copyOf(archived);
    }

    /** 把会话加入归档集，返回更新后的全集。 */
    public List<String> archiveSession(String sessionId) {
        archived.add(sessionId);
        save();
        return List.copyOf(archived);
    }

    private static Map<String, Object> view(Workspace w) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("workspaceId", w.id());
        v.put("path", w.path());
        v.put("title", w.title());
        v.put("sessionIds", List.copyOf(w.sessionIds()));
        v.put("createdAt", w.createdAt());
        v.put("updatedAt", w.updatedAt());
        return v;
    }

    // ---- 持久化 ----

    @SuppressWarnings("unchecked")
    private void load() {
        if (!java.nio.file.Files.isReadable(storeFile)) return;
        try {
            var root = MAPPER.readTree(storeFile.toFile());
            var arr = root.path("workspaces");
            if (arr.isArray()) {
                for (var n : arr) {
                    String id = n.path("id").asText();
                    List<String> sids = new ArrayList<>();
                    var sa = n.path("sessionIds");
                    if (sa.isArray()) for (var s : sa) sids.add(s.asText());
                    Workspace w = new Workspace(id, n.path("path").asText(), n.path("title").asText(),
                            sids, n.path("createdAt").asText(), n.path("updatedAt").asText());
                    byId.put(id, w);
                    pathToId.put(w.path(), id);
                }
            }
            var ar = root.path("archived");
            if (ar.isArray()) for (var s : ar) archived.add(s.asText());
            log.info("Loaded {} workspace(s), {} archived session(s)", byId.size(), archived.size());
        } catch (Exception e) {
            log.warn("Failed to load workspace: {}", e.toString());
        }
    }

    private void save() {
        try {
            java.nio.file.Files.createDirectories(storeFile.getParent());
            var root = MAPPER.createObjectNode();
            var arr = root.putArray("workspaces");
            for (Workspace w : byId.values()) {
                var o = arr.addObject();
                o.put("id", w.id());
                o.put("path", w.path());
                o.put("title", w.title());
                var sa = o.putArray("sessionIds");
                for (String s : w.sessionIds()) sa.add(s);
                o.put("createdAt", w.createdAt());
                o.put("updatedAt", w.updatedAt());
            }
            var ar = root.putArray("archived");
            for (String s : archived) ar.add(s);
            java.nio.file.Files.writeString(storeFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("Failed to persist workspace: {}", e.toString());
        }
    }
}
