package com.deepseek.dsh.guard;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.exception.ToolException;
import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具中间件测试 —— 重复调用提醒 + 超时策略。
 */
class GuardTest {

    private ToolExecutionRequest req(String name, Map<String, Object> args) {
        return new ToolExecutionRequest(name, "c1", args, null);
    }

    private com.deepseek.dsh.core.middleware.Middleware.Next<ToolExecutionRequest, ToolExecutionResult>
            ok() {
        return r -> ToolExecutionResult.ok(r.toolCallId(), "done");
    }

    @Test
    void 重复调用达阈值追加提醒() {
        var guard = new RepeatToolReminderGuard(2);
        var args = Map.<String, Object>of("x", 1);
        // 第1次：不提醒
        var r1 = guard.handle(req("bash", args), ok());
        assertFalse(r1.text().contains("提示"));
        // 第2次相同参数：repeatCount=1，未达2
        var r2 = guard.handle(req("bash", args), ok());
        assertFalse(r2.text().contains("提示"));
        // 第3次相同：repeatCount=2 >= 2 → 提醒
        var r3 = guard.handle(req("bash", args), ok());
        assertTrue(r3.text().contains("提示"), "应追加重复调用提醒");
    }

    @Test
    void 不同参数不累计重复() {
        var guard = new RepeatToolReminderGuard(2);
        guard.handle(req("bash", Map.<String, Object>of("x", 1)), ok());
        guard.handle(req("bash", Map.<String, Object>of("x", 2)), ok());
        guard.handle(req("bash", Map.<String, Object>of("x", 3)), ok());
        // 参数不同 → 无提醒（第4次同参数验证计数器已重置）
        var r = guard.handle(req("bash", Map.<String, Object>of("x", 3)), ok());
        assertFalse(r.text().contains("提示"));
    }

    @Test
    void 超时策略放行快调用() {
        var guard = new TimeoutPolicyGuard(10);
        var r = guard.handle(req("fast", Map.<String, Object>of()), ok());
        assertEquals("done", r.text());
    }

    @Test
    void 超时策略超时抛可恢复异常() {
        var guard = new TimeoutPolicyGuard(1);
        var slow = req("slow", Map.<String, Object>of());
        com.deepseek.dsh.core.middleware.Middleware.Next<ToolExecutionRequest, ToolExecutionResult> next =
                request -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return ToolExecutionResult.ok(request.toolCallId(), "不应到达");
                };
        var ex = assertThrows(ToolException.class, () -> guard.handle(slow, next));
        assertTrue(ex.getMessage().contains("超时"));
        assertTrue(ex.isRecoverable());
    }
}
