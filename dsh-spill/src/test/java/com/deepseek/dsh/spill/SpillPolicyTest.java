package com.deepseek.dsh.spill;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.registry.ToolContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外溢策略中间件测试 —— 超大结果外溢+预览替换、小结果不动、read 跳过、无后端 no-op。
 */
class SpillPolicyTest {

    private ToolContext ctx() {
        return new ToolContext(SessionId.of("s1"), null, null);
    }

    private ToolExecutionRequest req(String tool, String callId) {
        return new ToolExecutionRequest(tool, callId, Map.of(), ctx());
    }

    private com.deepseek.dsh.core.middleware.Middleware.Next<ToolExecutionRequest, ToolExecutionResult>
            returning(String text) {
        return r -> ToolExecutionResult.ok(r.toolCallId(), text);
    }

    @Test
    void 小结果原样保留(@TempDir Path dir) {
        var store = new LocalSpillStore(dir);
        var policy = new SpillPolicy(4096, store);
        var result = policy.handle(req("bash", "c1"), returning("short"));
        assertEquals("short", result.text());
        assertFalse(result.isError());
    }

    @Test
    void 超大结果被替换为预览加通知(@TempDir Path dir) {
        var store = new LocalSpillStore(dir);
        var policy = new SpillPolicy(1000, store);
        String big = "X".repeat(5000);
        var result = policy.handle(req("bash", "c2"), returning(big));
        assertNotEquals(big, result.text());
        assertTrue(result.text().contains("full result saved to"));
        // 替换文本不得超过上限
        assertTrue(result.text().getBytes().length <= 1000);
    }

    @Test
    void 极小上限保留内联因通知自身超限(@TempDir Path dir) {
        var store = new LocalSpillStore(dir);
        var policy = new SpillPolicy(10, store);
        String big = "Y".repeat(5000);
        var result = policy.handle(req("bash", "c5"), returning(big));
        // 通知本身超过 10 字节上限 → 无有界替换，保留内联（文档定义行为）
        assertEquals(big, result.text());
    }

    @Test
    void read工具被跳过(@TempDir Path dir) {
        var store = new LocalSpillStore(dir);
        var policy = new SpillPolicy(10, store);
        String big = "Y".repeat(5000);
        var result = policy.handle(req("read", "c3"), returning(big));
        assertEquals(big, result.text());
    }

    @Test
    void 无后端时noOp() {
        var policy = new SpillPolicy(10, null);
        String big = "Z".repeat(5000);
        var result = policy.handle(req("bash", "c4"), returning(big));
        assertEquals(big, result.text());
    }
}
