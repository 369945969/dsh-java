package com.deepseek.dsh.llm.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型档案注册表测试 —— 添加/切换/删除/持久化/运行时同步。
 */
class ModelProfileStoreTest {

    @Test
    void 从环境变量种入默认档案(@TempDir Path dir) {
        Path cfg = dir.resolve("model-config.json");
        var runtime = new ModelConfig("sk-env", "https://api.deepseek.com", "deepseek-chat");
        var store = new ModelProfileStore(cfg, runtime, "sk-env", "https://api.deepseek.com", "deepseek-chat");

        assertEquals(1, store.profiles().size());
        assertEquals(store.profiles().get(0).id(), store.activeId());
        // 运行时配置已同步
        assertEquals("sk-env", runtime.apiKey());
        assertEquals("deepseek-chat", runtime.model());
        // 持久化文件已生成
        assertTrue(Files.exists(cfg));
    }

    @Test
    void 添加并切换自定义模型(@TempDir Path dir) {
        var runtime = new ModelConfig("", "", "");
        var store = new ModelProfileStore(dir.resolve("model-config.json"), runtime, "", "", "");

        ModelProfile p1 = store.add("glm-5.2", "sk-glm", "https://dashscope.aliyuncs.com/compatible-mode/v1", "glm-5.2");
        ModelProfile p2 = store.add("deepseek", "sk-ds", "https://api.deepseek.com", "deepseek-chat");

        assertEquals(2, store.profiles().size());
        // 首个自动设为活跃
        assertEquals(p1.id(), store.activeId());
        // 切换到 p2，运行时配置随之更新
        assertTrue(store.setActive(p2.id()));
        assertEquals(p2.id(), store.activeId());
        assertEquals("sk-ds", runtime.apiKey());
        assertEquals("deepseek-chat", runtime.model());
    }

    @Test
    void 删除活跃档案后回退到首个(@TempDir Path dir) {
        var runtime = new ModelConfig("", "", "");
        var store = new ModelProfileStore(dir.resolve("model-config.json"), runtime, "", "", "");
        ModelProfile p1 = store.add("a", "k1", "u1", "m1");
        store.add("b", "k2", "u2", "m2");
        assertTrue(store.setActive(p1.id()));

        assertTrue(store.delete(p1.id()));
        assertEquals(1, store.profiles().size());
        // 删除活跃后回退到剩余首个
        assertEquals(store.profiles().get(0).id(), store.activeId());
    }

    @Test
    void 重启后从持久化文件加载(@TempDir Path dir) {
        Path cfg = dir.resolve("model-config.json");
        var runtime1 = new ModelConfig("", "", "");
        var store1 = new ModelProfileStore(cfg, runtime1, "", "", "");
        ModelProfile p = store1.add("glm", "sk-x", "url", "glm-5.2");
        store1.setActive(p.id());

        // 新实例从同一文件加载
        var runtime2 = new ModelConfig("", "", "");
        var store2 = new ModelProfileStore(cfg, runtime2, "", "", "");
        assertEquals(1, store2.profiles().size());
        assertEquals(p.id(), store2.activeId());
        assertEquals("sk-x", runtime2.apiKey());
        assertEquals("glm-5.2", runtime2.model());
    }

    @Test
    void 页面配置覆盖环境变量初值(@TempDir Path dir) {
        var runtime = new ModelConfig("sk-env", "https://api.deepseek.com", "deepseek-chat");
        var store = new ModelProfileStore(dir.resolve("model-config.json"), runtime,
                "sk-env", "https://api.deepseek.com", "deepseek-chat");
        // 添加并切到自定义模型 → 覆盖环境变量
        ModelProfile p = store.add("glm", "sk-glm", "https://dashscope.aliyuncs.com/compatible-mode/v1", "glm-5.2");
        store.setActive(p.id());
        assertEquals("sk-glm", runtime.apiKey());
        assertEquals("glm-5.2", runtime.model());
    }
}
