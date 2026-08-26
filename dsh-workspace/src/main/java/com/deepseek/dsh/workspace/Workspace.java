package com.deepseek.dsh.workspace;

import java.time.Instant;
import java.util.List;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 工作区 —— 一个目录路径关联的会话归集实体，对应原 Harness 的 {@code Workspace}。
 *
 * <p>路径在创建时归一化（realpath）并不可变；会话可附加/分离/重排。
 *
 * @param id         工作区 ID
 * @param path       归一化绝对路径（创建后不可变）
 * @param title      标题
 * @param createdAt  创建时间（ISO-8601）
 * @param updatedAt  更新时间（ISO-8601）
 * @param sessionIds 关联的会话 ID 列表
 */
public record Workspace(
        WorkspaceId id,
        String path,
        String title,
        String createdAt,
        String updatedAt,
        List<SessionId> sessionIds
) {
    public Workspace {
        if (sessionIds == null) sessionIds = List.of();
        else sessionIds = List.copyOf(sessionIds);
    }
}
