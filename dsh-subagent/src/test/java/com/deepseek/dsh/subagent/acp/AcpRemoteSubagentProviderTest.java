package com.deepseek.dsh.subagent.acp;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACP 远程子 agent 提供者测试 —— 远程运行时不可达时降级为失败结果。
 */
class AcpRemoteSubagentProviderTest {

    /** 假 agent：仅提供人格名/提示，远程执行由 provider 外化。 */
    static final class FakeAgent implements Agent {
        @Override public String name() { return "remote-persona"; }
        @Override public String systemPrompt() { return "你是远程子 agent"; }
        @Override
        public String run(SessionId sessionId, ScopeKey scopeKey, Context ctx, String userMessage) {
            return "不应到达本地执行";
        }
    }

    @Test
    void 远程运行时不可达时返回失败结果() {
        // 不存在的命令：进程启动失败
        var provider = new AcpRemoteSubagentProvider("no-such-runtime-xyz-123");
        var result = provider.delegate(
                SessionId.of("parent"), ScopeKey.random(),
                Context.root(), new FakeAgent(), "子任务");
        assertFalse(result.success());
        assertTrue(result.report().contains("failed"));
        provider.close();
    }
}
