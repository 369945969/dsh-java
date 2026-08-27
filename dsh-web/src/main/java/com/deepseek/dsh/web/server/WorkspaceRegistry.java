package com.deepseek.dsh.web.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 工作区注册表（内存）—— 对应原 Harness 的 {@code ctx.workspaceRegistry}。
 *
 * <p>工作区 = 一个目录路径 + 其下会话集合。{@code workspace.create({path})} 采纳一个真实目录，
 * 路径已存在则复用（created:false），否则新建。{@code session.create} 把会话挂到工作区。
 *
 * <p>设计模式：注册表（按路径/工作区 id 索引的工作区记录）。
 */
@Component
public class WorkspaceRegistry {

    private record Workspace(String id, String path, String title, List<String> sessionIds, String createdAt, String updatedAt) {}

    private final Map<String, Workspace> byId = new ConcurrentHashMap<>();
    private final Map<String, String> pathToId = new ConcurrentHashMap<>();

    /** 按路径采纳工作区：已存在则复用，否则新建。返回 {workspace, created}。 */
    public Map<String, Object> ensure(String path) {
        synchronized (pathToId) {
            String existing = pathToId.get(path);
            if (existing != null) {
                Workspace w = byId.get(existing);
                return Map.of("workspace", view(w), "created", false);
            }
            String id = "ws-" + UUID.randomUUID().toString().substring(0, 8);
            String now = java.time.Instant.now().toString();
            String title = new java.io.File(path).getName();
            if (title == null || title.isEmpty()) title = path;
            Workspace w = new Workspace(id, path, title, new ArrayList<>(), now, now);
            byId.put(id, w);
            pathToId.put(path, id);
            return Map.of("workspace", view(w), "created", true);
        }
    }

    /** 取工作区视图；不存在返回 null。 */
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
        return view(updated);
    }

    /** 全部工作区视图。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Workspace w : byId.values()) items.add(view(w));
        return items;
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
}
