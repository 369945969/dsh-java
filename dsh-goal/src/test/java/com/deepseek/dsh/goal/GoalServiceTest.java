package com.deepseek.dsh.goal;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 目标服务测试 —— arm/current/advanceRound/setPhase/disarm + Goal.exhausted。
 */
class GoalServiceTest {

    @Test
    void 装备与查询当前目标() {
        var svc = new GoalService();
        var sid = SessionId.of("s1");
        svc.arm(sid, "完成重构");
        var g = svc.current(sid).orElseThrow();
        assertEquals("完成重构", g.objective());
        assertEquals(GoalPhase.ACTIVE, g.phase());
        assertEquals(0, g.roundsCompleted());
    }

    @Test
    void 推进轮次与阶段切换() {
        var svc = new GoalService();
        var sid = SessionId.of("s1");
        svc.arm(sid, "目标");
        svc.advanceRound(sid);
        svc.advanceRound(sid);
        assertEquals(2, svc.current(sid).orElseThrow().roundsCompleted());

        svc.setPhase(sid, GoalPhase.COMPLETE);
        assertEquals(GoalPhase.COMPLETE, svc.current(sid).orElseThrow().phase());
    }

    @Test
    void 解除后无当前目标() {
        var svc = new GoalService();
        var sid = SessionId.of("s1");
        svc.arm(sid, "x");
        svc.disarm(sid);
        assertTrue(svc.current(sid).isEmpty());
    }

    @Test
    void Goal轮数上限耗尽() {
        var g = Goal.armed(SessionId.of("s1"), "目标");
        assertFalse(g.exhausted());
        for (int i = 0; i < 5; i++) g = g.advanceRound();
        assertTrue(g.exhausted());
    }

    @Test
    void 推进未装备会话无副作用() {
        var svc = new GoalService();
        var sid = SessionId.of("none");
        // computeIfPresent 不存在则不插入
        svc.advanceRound(sid);
        assertTrue(svc.current(sid).isEmpty());
    }
}
