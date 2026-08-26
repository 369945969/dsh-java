package com.deepseek.dsh.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常层次体系测试 —— 验证上下文携带与可恢复性判定。
 */
class DshExceptionTest {

    @Test
    void LlmException区分可恢复性() {
        LlmException serverError = new LlmException("deepseek-chat", 503, "服务不可用", null);
        assertTrue(serverError.isRecoverable());
        assertEquals(503, serverError.httpStatus());
        assertEquals("llm.chat", serverError.operation());
        assertEquals("deepseek-chat", serverError.target());

        LlmException authError = new LlmException("deepseek-chat", 401, "鉴权失败", null);
        assertFalse(authError.isRecoverable());
    }

    @Test
    void ToolException携带工具上下文() {
        ToolException e = new ToolException("bash", "call-1", "命令超时", null, true);
        assertEquals("bash", e.toolName());
        assertEquals("call-1", e.toolCallId());
        assertEquals("tool.bash", e.operation());
        assertTrue(e.isRecoverable());
    }

    @Test
    void toString包含操作与目标() {
        SessionException e = new SessionException("session.append", "sess-1", "写入失败", null);
        String str = e.toString();
        assertTrue(str.contains("session.append"));
        assertTrue(str.contains("sess-1"));
    }
}
