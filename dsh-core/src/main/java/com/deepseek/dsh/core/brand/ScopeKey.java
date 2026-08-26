package com.deepseek.dsh.core.brand;

import java.util.UUID;

/**
 * 单个 agent 作用域（活跃 agent 实例）的品牌化 ID。
 */
public final class ScopeKey extends Branded<UUID, ScopeKey.Tag> {
    private ScopeKey(UUID value) {
        super(value);
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static ScopeKey of(UUID raw) {
        return new ScopeKey(raw);
    }

    public static ScopeKey random() {
        return new ScopeKey(UUID.randomUUID());
    }

    /** 幻影标签标记。 */
    public static final class Tag {}
}
