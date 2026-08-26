package com.deepseek.dsh.teams;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 默认团队协作提供者测试 —— 并行 fan-out + 聚合 + 部分降级。
 */
class DefaultTeamsProviderTest {

    /** 假 agent：回显任务 + 计数，模拟真实子 agent。 */
    static final class FakeAgent implements Agent {
        private final String name;
        final AtomicInteger calls = new AtomicInteger(0);

        FakeAgent(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public String systemPrompt() { return "你是 " + name; }

        @Override
        public String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) {
            calls.incrementAndGet();
            return name + " 处理: " + userMessage;
        }
    }

    @Test
    void 多成员并行执行并聚合() {
        var teams = new DefaultTeamsProvider();
        FakeAgent a = new FakeAgent("审查员");
        FakeAgent b = new FakeAgent("测试员");
        teams.registerMember(a.name(), a);
        teams.registerMember(b.name(), b);

        var result = teams.runTeamTask("检查模块X");
        assertEquals(2, result.reports().size());
        assertTrue(result.allSucceeded());
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
        assertTrue(result.summary().contains("成员 2 名"));
        assertTrue(result.summary().contains("成功 2/2"));
    }

    @Test
    void 无成员返回空结果() {
        var teams = new DefaultTeamsProvider();
        var result = teams.runTeamTask("任务");
        assertTrue(result.reports().isEmpty());
        assertTrue(result.allSucceeded());
    }

    @Test
    void 重复注册同名抛异常() {
        var teams = new DefaultTeamsProvider();
        teams.registerMember("x", new FakeAgent("x"));
        assertThrows(IllegalStateException.class,
                () -> teams.registerMember("x", new FakeAgent("x")));
    }

    @Test
    void 成员列表反映注册() {
        var teams = new DefaultTeamsProvider();
        teams.registerMember("a", new FakeAgent("a"));
        teams.registerMember("b", new FakeAgent("b"));
        assertEquals(2, teams.memberNames().size());
    }
}
