package com.deepseek.dsh.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 本地工作区注册表 —— 对应原 Harness 的 {@code workspace}（本地后端）。
 *
 * <p>持久化到 {@code dataDir/workspaces.json}：一个工作区数组，
 * 每条含 id/path/title/createdAt/updatedAt/sessionIds。路径以 realpath 归一化。
 * 同一路径只创建一个工作区（create 时去重）。
 *
 * <p>设计模式：注册表 + 仓储（JSON 文件持久化）+ 模板方法（插件基类）。
 */
public final class LocalWorkspaceRegistry
        extends AbstractCapabilityPlugin<WorkspaceRegistry>
        implements WorkspaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkspaceRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configFile;
    private final ConcurrentMap<WorkspaceId, Workspace> workspaces = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WorkspaceId> pathIndex = new ConcurrentHashMap<>();

    public LocalWorkspaceRegistry(Path dataDir) {
        this.configFile = dataDir.resolve("workspaces.json");
        load();
    }

    @Override
    protected Class<WorkspaceRegistry> serviceType() {
        return WorkspaceRegistry.class;
    }

    @Override
    public synchronized Workspace create(String path, String title) {
        String canonical = canonicalize(path);
        WorkspaceId existingId = pathIndex.get(canonical);
        if (existingId != null) {
            return workspaces.get(existingId);
        }
        WorkspaceId id = WorkspaceId.random();
        String now = Instant.now().toString();
        Workspace ws = new Workspace(id, canonical, title == null ? canonical : title, now, now, List.of());
        workspaces.put(id, ws);
        pathIndex.put(canonical, id);
        persist();
        return ws;
    }

    @Override
    public Optional<Workspace> get(WorkspaceId id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    @Override
    public List<Workspace> list() {
        return List.copyOf(workspaces.values());
    }

    @Override
    public synchronized boolean delete(WorkspaceId id) {
        Workspace removed = workspaces.remove(id);
        if (removed == null) return false;
        pathIndex.remove(removed.path());
        persist();
        return true;
    }

    @Override
    public Optional<Workspace> resolveByPath(String path) {
        return Optional.ofNullable(pathIndex.get(canonicalize(path)))
                .map(workspaces::get);
    }

    @Override
    public synchronized Workspace setTitle(WorkspaceId id, String title) {
        Workspace ws = require(id);
        Workspace updated = new Workspace(ws.id(), ws.path(), title, ws.createdAt(),
                Instant.now().toString(), ws.sessionIds());
        workspaces.put(id, updated);
        persist();
        return updated;
    }

    @Override
    public synchronized Workspace attachSession(WorkspaceId id, SessionId sessionId) {
        Workspace ws = require(id);
        List<SessionId> sids = new ArrayList<>(ws.sessionIds());
        if (!sids.contains(sessionId)) sids.add(sessionId);
        Workspace updated = new Workspace(ws.id(), ws.path(), ws.title(), ws.createdAt(),
                Instant.now().toString(), sids);
        workspaces.put(id, updated);
        persist();
        return updated;
    }

    @Override
    public synchronized Workspace detachSession(WorkspaceId id, SessionId sessionId) {
        Workspace ws = require(id);
        List<SessionId> sids = new ArrayList<>(ws.sessionIds().stream()
                .filter(s -> !s.equals(sessionId)).toList());
        Workspace updated = new Workspace(ws.id(), ws.path(), ws.title(), ws.createdAt(),
                Instant.now().toString(), sids);
        workspaces.put(id, updated);
        persist();
        return updated;
    }

    private Workspace require(WorkspaceId id) {
        Workspace ws = workspaces.get(id);
        if (ws == null) throw new IllegalArgumentException("工作区不存在: " + id.value());
        return ws;
    }

    private static String canonicalize(String path) {
        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    private void load() {
        if (!Files.isReadable(configFile)) return;
        try {
            var root = MAPPER.readTree(Files.readString(configFile));
            var arr = root.path("workspaces");
            if (arr.isArray()) {
                for (var n : arr) {
                    WorkspaceId id = WorkspaceId.of(n.path("id").asText());
                    List<SessionId> sids = new ArrayList<>();
                    var sArr = n.path("sessionIds");
                    if (sArr.isArray()) {
                        for (var s : sArr) sids.add(SessionId.of(s.asText()));
                    }
                    Workspace ws = new Workspace(id, n.path("path").asText(),
                            n.path("title").asText(), n.path("createdAt").asText(),
                            n.path("updatedAt").asText(), sids);
                    workspaces.put(id, ws);
                    pathIndex.put(ws.path(), id);
                }
            }
        } catch (Exception e) {
            log.warn("加载工作区失败: {}", e.toString());
        }
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(configFile.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode arr = root.putArray("workspaces");
            for (Workspace ws : workspaces.values()) {
                ObjectNode o = arr.addObject();
                o.put("id", ws.id().value());
                o.put("path", ws.path());
                o.put("title", ws.title());
                o.put("createdAt", ws.createdAt());
                o.put("updatedAt", ws.updatedAt());
                ArrayNode sids = o.putArray("sessionIds");
                for (SessionId s : ws.sessionIds()) sids.add(s.value());
            }
            Files.writeString(configFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            log.warn("持久化工作区失败: {}", e.toString());
        }
    }
}
