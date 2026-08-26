package com.deepseek.dsh.interaction.permission;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限预设 + 默认权限服务测试 —— decide 判定 + 预设链优先级 + 注册注销。
 */
class PermissionServiceTest {

    @Test
    void 预设deny优先于ask与allow() {
        var p = new PermissionPreset("p", Set.of("read"), Set.of("rm"), Set.of("edit"));
        assertEquals(PermissionPreset.Decision.DENY, p.decide("rm"));
        assertEquals(PermissionPreset.Decision.ASK, p.decide("edit"));
        assertEquals(PermissionPreset.Decision.ALLOW, p.decide("read"));
    }

    @Test
    void allow为空表示全部允许() {
        var p = new PermissionPreset("p", Set.of(), Set.of(), Set.of());
        assertEquals(PermissionPreset.Decision.ALLOW, p.decide("anything"));
        assertEquals(PermissionPreset.Decision.ALLOW, p.decide("read"));
    }

    @Test
    void 未在allow列表的工具默认拒绝() {
        var p = new PermissionPreset("p", Set.of("read"), Set.of(), Set.of());
        assertEquals(PermissionPreset.Decision.DENY, p.decide("write"));
    }

    @Test
    void 服务链首个非allow决策生效() {
        var svc = new PermissionService.DefaultPermissionService();
        // 默认无预设 → 全部放行
        assertEquals(PermissionPreset.Decision.ALLOW, svc.check("anything", "k"));

        var d = svc.registerPreset(new PermissionPreset("strict", Set.of(), Set.of("rm"), Set.of()));
        assertEquals(PermissionPreset.Decision.DENY, svc.check("rm", "k"));
        assertEquals(PermissionPreset.Decision.ALLOW, svc.check("read", "k"));
        // 注销预设后恢复放行
        d.dispose();
        assertEquals(PermissionPreset.Decision.ALLOW, svc.check("rm", "k"));
    }
}
