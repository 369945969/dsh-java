package com.deepseek.dsh.workflow;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流引擎测试 —— 虚拟线程异步任务。
 */
class WorkflowServiceTest {

    @Test
    void 异步任务完成() throws Exception {
        var provider = new WorkerThreadWorkflowProvider();
        var task = WorkflowTask.of("t-1", "测试任务", "payload");
        var future = provider.submit(task);
        var result = future.get(5, TimeUnit.SECONDS);
        assertEquals("t-1", result.taskId());
        assertTrue(result.success());
        assertEquals("DONE", provider.status("t-1"));
    }
}
