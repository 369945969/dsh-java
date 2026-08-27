package com.deepseek.dsh.web.api;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.llm.config.ModelProfile;
import com.deepseek.dsh.llm.config.ModelProfileStore;
import com.deepseek.dsh.web.server.AgentContextHolder;

/**
 * 模型配置控制器 —— Web 设置页后端，支持添加/选择/删除自定义模型。
 *
 * <p>对应「页面配置方式」：用户可在前端管理多个模型档案（不同 provider/key/模型），
 * 切换当前活跃模型，变更即时生效（下一回合即用新配置）并持久化。
 *
 * <p>设计模式：前端控制器 + REST 资源（模型档案 CRUD）。
 */
@RestController
@RequestMapping("/api/config/models")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final AgentContextHolder holder;

    public ConfigController(AgentContextHolder holder) {
        this.holder = holder;
    }

    private ModelProfileStore store() {
        Context ctx = holder.context();
        return ctx.get(ModelProfileStore.class)
                .orElseThrow(() -> new IllegalStateException("模型配置服务未注册"));
    }

    /** 列出全部模型档案（API Key 脱敏）+ 当前活跃 ID。 */
    @GetMapping
    public Map<String, Object> list() {
        ModelProfileStore s = store();
        List<Map<String, Object>> items = s.profiles().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.id(),
                        "displayName", p.displayName(),
                        "baseUrl", p.baseUrl(),
                        "model", p.model(),
                        "hasKey", !p.apiKey().isBlank(),
                        "apiKeyMasked", mask(p.apiKey())))
                .toList();
        return Map.of(
                "profiles", items,
                "activeId", s.activeId() == null ? "" : s.activeId(),
                "count", items.size());
    }

    /** 添加一个自定义模型档案。 */
    @PostMapping
    public Map<String, Object> add(@RequestBody ModelProfileRequest req) {
        ModelProfile p = store().add(req.displayName(), req.apiKey(), req.baseUrl(), req.model());
        log.info("Adding model profile: {} ({})", p.displayName(), p.model());
        return Map.of("id", p.id(), "displayName", p.displayName(), "activeId", store().activeId());
    }

    /** 更新一个模型档案。 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody ModelProfileRequest req) {
        boolean ok = store().update(id, req.displayName(), req.apiKey(), req.baseUrl(), req.model());
        return Map.of("updated", ok);
    }

    /** 删除一个模型档案。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = store().delete(id);
        return Map.of("deleted", ok, "activeId", store().activeId() == null ? "" : store().activeId());
    }

    /** 设为当前活跃模型。 */
    @PutMapping("/active")
    public Map<String, Object> setActive(@RequestBody Map<String, String> body) {
        String id = body.get("id");
        boolean ok = store().setActive(id);
        return Map.of("activated", ok, "activeId", store().activeId() == null ? "" : store().activeId());
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "*".repeat(key.length());
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    /** 添加/更新模型档案的请求体。 */
    public record ModelProfileRequest(
            String displayName, String apiKey, String baseUrl, String model) {}
}
