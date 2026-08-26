package com.deepseek.dsh.capability.jobs;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后台任务运行时测试。
 */
class JobServiceTest {

    @Test
    void 提交并完成后台任务() throws Exception {
        var jobs = new JobService();
        String id = jobs.submit("owner-1", "测试任务", () -> "完成结果");
        var job = jobs.get(id).orElseThrow();
        // 等待异步完成
        Thread.sleep(200);
        assertEquals("完成结果", jobs.output(id));
        var list = jobs.listByOwner("owner-1");
        assertEquals(1, list.size());
        jobs.shutdown();
    }

    @Test
    void 取消运行中任务() throws Exception {
        var jobs = new JobService();
        String id = jobs.submit("owner-2", "长任务", () -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "不应到达";
        });
        assertTrue(jobs.cancel(id));
        Thread.sleep(100);
        var job = jobs.get(id).orElseThrow();
        assertEquals(JobService.JobStatus.CANCELLED, job.status());
        jobs.shutdown();
    }
}
