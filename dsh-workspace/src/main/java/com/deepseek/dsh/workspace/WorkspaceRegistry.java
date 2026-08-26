package com.deepseek.dsh.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Service;

/**
 * 工作区注册表能力缝 —— 对应原 Harness 的 {@code ctx.workspaceRegistry}。
 *
 * <p>管理按目录路径归集的工作区实体：创建/查询/列出/删除/会话附加与重排。
 * 路径以 realpath 归一化作为唯一性标准。
 *
 * <p>设计模式：注册表 + 仓储（持久化）+ 策略（可换后端）。
 */
public interface WorkspaceRegistry extends Service {

    /** 创建一个工作区（路径归一化；同路径已存在则返回已有）。 */
    Workspace create(String path, String title);

    /** 按 ID 查询工作区。 */
    Optional<Workspace> get(WorkspaceId id);

    /** 列出全部工作区。 */
    List<Workspace> list();

    /** 删除一个工作区（按 ID）。 */
    boolean delete(WorkspaceId id);

    /** 按归一化路径解析工作区。 */
    Optional<Workspace> resolveByPath(String path);

    /** 修改工作区标题。 */
    Workspace setTitle(WorkspaceId id, String title);

    /** 向工作区附加一个会话。 */
    Workspace attachSession(WorkspaceId id, SessionId sessionId);

    /** 从工作区分离一个会话。 */
    Workspace detachSession(WorkspaceId id, SessionId sessionId);
}
