package com.deepseek.dsh.llm.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型档案持久化后端 —— 把 {activeId, profiles:[...]} 的读写从 ModelProfileStore 解耦。
 *
 * <p>实现：{@link FileModelProfileBackend}（model-config.json 文件，默认）/
 * {@code MysqlModelProfileBackend}（dsh-storage-mysql，model_profile 表）。
 * DSH_STORAGE=mysql 时由 BaseBundle 装配 DB 后端。
 *
 * <p>设计模式：策略（持久化后端可插拔）。
 */
public interface ModelProfileBackend {

    /** 加载 {activeId, profiles:[...]} JSON 树；无配置返回 null。 */
    JsonNode load();

    /** 持久化 {activeId, profiles:[...]} JSON 树。 */
    void persist(JsonNode root);
}
