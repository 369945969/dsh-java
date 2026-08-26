package com.deepseek.dsh.subprocess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地子进程提供者测试 —— 可执行解析 + 托管 spawn。
 */
class LocalSubprocessProviderTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    void 解析系统可执行() {
        var provider = new LocalSubprocessProvider();
        String resolved = provider.resolveExecutable("echo", Map.of()).join();
        assertTrue(resolved.endsWith("/echo"));
    }

    @Test
    void 解析绝对路径() {
        var provider = new LocalSubprocessProvider();
        String resolved = provider.resolveExecutable("/bin/echo", Map.of()).join();
        assertEquals("/bin/echo", resolved);
    }

    @Test
    void 不存在命令抛异常() {
        var provider = new LocalSubprocessProvider();
        assertThrows(Exception.class,
                () -> provider.resolveExecutable("no-such-cmd-xyz-123", Map.of()).join());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void spawn收集输出() {
        var provider = new LocalSubprocessProvider();
        var spec = new SubprocessService.SubprocessSpawnSpec(
                List.of("echo", "hi"), null, Map.of(), true, true, 5);
        var handle = provider.spawn(spec);
        assertTrue(handle.done().isDone());
        assertEquals(0, handle.exitCode());
        assertTrue(handle.stdout().contains("hi"));
    }

    @Test
    void 清洗父环境剔除凭据形与DSH前缀() {
        var env = SubprocessService.scrubbedParentEnv();
        // DSH_* 不应存在
        for (String k : env.keySet()) {
            assertFalse(k.toUpperCase().startsWith("DSH_"), "泄漏: " + k);
            assertFalse(java.util.regex.Pattern.compile("KEY|PASSWORD|SECRET|TOKEN",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(k).find(),
                    "泄漏凭据形: " + k);
        }
    }
}
