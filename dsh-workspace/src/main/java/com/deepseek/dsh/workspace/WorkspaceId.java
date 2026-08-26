package com.deepseek.dsh.workspace;

import com.deepseek.dsh.core.brand.Branded;

/**
 * 工作区 ID —— 品牌化的 UUID 字符串，对应原 Harness 的 {@code WorkspaceId}。
 */
public final class WorkspaceId extends Branded<String, WorkspaceId.Tag> {
    private WorkspaceId(String value) { super(value); }

    public static WorkspaceId of(String raw) { return new WorkspaceId(raw); }

    public static WorkspaceId random() { return new WorkspaceId(java.util.UUID.randomUUID().toString()); }

    public static final class Tag {}
}
