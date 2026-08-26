package com.deepseek.dsh.core.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProcessRunner 集成测试 —— 验证共用进程执行器的正常执行、超时与异常。
 */
class ProcessRunnerTest {

    @Test
    void 正常执行并捕获输出() {
        ExecutionResult result = ProcessRunner.run(
                new String[]{"bash", "-c", "echo hello"},
                null, null, 10, "test");
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout().trim());
        assertFalse(result.timedOut());
    }

    @Test
    void 超时被杀() {
        ExecutionResult result = ProcessRunner.run(
                new String[]{"bash", "-c", "sleep 10"},
                null, null, 1, "test");
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void 非零退出码() {
        ExecutionResult result = ProcessRunner.run(
                new String[]{"bash", "-c", "exit 3"},
                null, null, 10, "test");
        assertEquals(3, result.exitCode());
        assertFalse(result.succeeded());
    }

    @Test
    void combinedOutput合并标准流() {
        ExecutionResult r = ExecutionResult.of("out", "err", 1);
        String combined = r.combinedOutput();
        assertTrue(combined.contains("out"));
        assertTrue(combined.contains("[stderr]"));
        assertTrue(combined.contains("err"));
        assertTrue(combined.contains("[exit=1]"));
    }
}
