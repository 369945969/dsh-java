package com.deepseek.dsh.testcase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.deepseek.dsh.sdk.client.HarnessClient;

/**
 * RPC 端到端验证驱动（基于 dsh SDK 客户端）—— 覆盖三大开发模式分组。
 *
 * <p>以子进程启动后端 RPC 服务端（{@code start-rpc.sh}，stdio JSON-RPC），
 * 通过 SDK 客户端外部访问，验证后端 RPC 全部功能并按「常用开发模式」分组：
 * <pre>
 * 【基础对话模式】 基础问候 / 完整返回响应 / 自定义模型调用
 * 【会话与记忆模式】 记忆保存 / fork保留记忆 / fork新session无记忆 /
 *                    查询session列表+单session状态 / 删除管理 / 上下文压缩
 * 【技能与编排模式】 技能发现(list/get) / subagent委派 / 多agent并行编排(team)
 * </pre>
 * 实时通信模式（SSE/WebSocket 并发+取消）见 web-e2e.sh / ws-e2e.py。
 *
 * <p>环境变量：DSH_RPC_CMD（start-rpc.sh 路径）、DEEPSEEK_API_KEY / DSH_BASE_URL /
 * DSH_MODEL / DSH_DATA_DIR（临时数据目录，驱动在此种子 skill 文件）。
 */
public final class RpcE2e {

    private static final AtomicInteger passed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    public static void main(String[] args) {
        String rpcCmd = System.getenv("DSH_RPC_CMD");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        String model = System.getenv("DSH_MODEL");
        String dataDir = System.getenv("DSH_DATA_DIR");

        if (rpcCmd == null || rpcCmd.isBlank()) { System.err.println("[rpc-e2e] DSH_RPC_CMD not set, skipping"); return; }
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            System.err.println("[rpc-e2e] DEEPSEEK_API_KEY/DSH_MODEL not set, skipping"); return;
        }

        // 种子 skill 文件供技能用例（FilesystemSkillProvider 每次 list() 扫描盘）
        if (dataDir != null) seedSkills(Path.of(dataDir));

        System.out.println("[rpc-e2e] Starting RPC server subprocess: " + rpcCmd);
        try (HarnessClient client = new HarnessClient(rpcCmd)) {
            group("基础对话模式");
            check("自定义模型调用 (initialize)", () -> {
                var init = client.initialize().join();
                assertEquals(model, init.model(), "initialize.model 应为配置的自定义模型");
                assertTrue(init.protocolVersion() != null && !init.protocolVersion().isBlank(), "protocolVersion 不应为空");
            });
            check("基础问候 (session/prompt)", () -> {
                var r = timeout(client.prompt(client.createSession().join(), "你好"));
                assertEquals("ok", r.status(), "状态应为 ok");
                assertTrue(r.reply() != null && !r.reply().isBlank(), "问候回复不应为空");
                System.out.println("    Reply: " + truncate(r.reply(), 100));
            });
            check("完整返回响应 (reply+tokens)", () -> {
                var r = timeout(client.prompt(client.createSession().join(), "用一句话介绍 Java"));
                assertTrue(r.reply() != null && r.reply().length() > 5, "完整回复应有内容");
                assertTrue(r.totalTokens() >= 0, "token 统计应存在");
            });

            group("会话与记忆模式");
            String parent = client.createSession().join();
            timeout(client.prompt(parent, "请记住：我的偏好是用中文回答，且密码是 1234。"));
            check("记忆保存 (history 非空)", () -> {
                var h = client.history(parent).join();
                assertNull(h.error(), "history 不应有错误");
                assertTrue(!h.messages().isEmpty(), "记忆应已保存（消息非空）");
            });
            check("fork 新 session 无记忆", () -> {
                String fresh = client.createSession().join();
                var h = client.history(fresh).join();
                assertTrue(h.messages().isEmpty(), "全新会话不应有记忆");
            });
            check("fork agent 保留记忆", () -> {
                var f = client.forkSession(parent).join();
                assertNull(f.error(), "fork 不应有错误");
                assertTrue(f.replayedEvents() > 0, "应回放父事件");
                var h = client.history(f.childSessionId()).join();
                assertTrue(!h.messages().isEmpty(), "fork 子会话应保留父记忆");
                boolean containsPwd = h.messages().stream()
                        .anyMatch(m -> m.toString().contains("1234"));
                assertTrue(containsPwd, "子会话记忆应含父会话内容(1234)");
            });
            check("查询 session 列表", () -> {
                var list = client.listSessions().join();
                assertTrue(list.sessionIds().contains(parent), "列表应含父会话");
                assertTrue(list.count() >= 1, "count 应 >=1");
            });
            check("单 session 状态查询", () -> {
                var h = client.history(parent).join();
                assertTrue(!h.messages().isEmpty(), "单会话状态查询应返回历史");
            });
            check("上下文压缩 (compact)", () -> {
                var c = client.compactSession(parent, 2048).join();
                assertNull(c.error(), "compact 不应有错误");
                assertTrue(c.before() > 0, "压缩前消息数应 >0");
                assertTrue(c.after() <= c.before(), "压缩后不应更多");
            });
            check("删除管理 (delete)", () -> {
                String d = client.createSession("del-target").join();
                assertTrue(client.deleteSession("del-target").join(), "首次删除应成功");
                assertTrue(!client.deleteSession("del-target").join(), "二次删除应 false");
            });

            group("技能与编排模式");
            check("技能发现 (skill/list)", () -> {
                var list = client.skillList().join();
                assertTrue(list.count() >= 2, "应发现至少 2 个种子技能，实际 " + list.count());
                assertTrue(list.skills().stream().anyMatch(s -> s.name().equals("code-review")), "应含 code-review");
                assertTrue(list.skills().stream().anyMatch(s -> s.name().equals("commit-helper")), "应含 commit-helper");
            });
            check("技能加载 (skill/get)", () -> {
                var g = client.skillGet("code-review").join();
                assertTrue(g.found(), "code-review 应可加载");
                assertTrue(g.rendered().contains("<skill_content"), "渲染应产出 skill_content 块");
            });
            check("subagent 委派 (subagent/task)", () -> {
                String sid = client.createSession().join();
                var r = client.subagentTask(sid, "用一句话总结：什么是 ReAct 模式").join();
                assertNull(r.error(), "委派不应有错误");
                assertTrue(r.success(), "委派应成功");
                assertTrue(r.report() != null && !r.report().isBlank(), "委派报告不应为空");
                System.out.println("    Report: " + truncate(r.report(), 100));
            });
            check("多 agent 并行编排 (team/run)", () -> {
                var r = client.teamRun("用一句话说明单元测试的价值").join();
                assertNull(r.error(), "team 不应有错误");
                assertEquals(2, r.memberCount(), "应有两名成员");
                assertTrue(r.allSucceeded(), "两名成员都应成功");
                assertTrue(r.summary() != null && !r.summary().isBlank(), "应有聚合摘要");
            });

            check("shutdown", () -> { client.shutdown().join(); });

            System.out.println();
            System.out.println("[rpc-e2e] Result: " + passed.get() + " passed, " + failed.get() + " failed");
            if (failed.get() > 0) System.exit(1);
        } catch (Exception e) {
            System.err.println("[rpc-e2e] Exception: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ---- 技能种子 ----
    private static void seedSkills(Path dataDir) {
        try {
            Path dir = dataDir.resolve("skills");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("code-review.md"), """
                    ---
                    name: code-review
                    description: 代码审查技能
                    whenToUse: 需要审查代码时
                    ---
                    # 代码审查
                    逐行检查代码质量、潜在缺陷与改进建议。
                    """);
            Files.writeString(dir.resolve("commit-helper.md"), """
                    ---
                    name: commit-helper
                    description: 提交信息生成技能
                    whenToUse: 需要生成 commit message 时
                    ---
                    # 提交信息
                    按 Conventional Commits 规范生成提交信息。
                    """);
            System.out.println("[rpc-e2e] Seeded 2 skill(s) to " + dir);
        } catch (Exception e) {
            System.err.println("[rpc-e2e] Seed skill failed (skill test may fail): " + e);
        }
    }

    // ---- 断言骨架 ----
    private interface Case { void run() throws Exception; }
    private static void group(String name) { System.out.println("\n  —— " + name + " ——"); }
    private static void check(String name, Case c) {
        try { c.run(); System.out.println("  [PASS] " + name); passed.incrementAndGet(); }
        catch (Throwable t) { System.out.println("  [FAIL] " + name + " — " + t.getMessage()); failed.incrementAndGet(); }
    }
    private static void assertTrue(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    private static void assertNull(Object o, String msg) { if (o != null) throw new AssertionError(msg + " (actual " + o + ")"); }
    private static void assertEquals(Object exp, Object act, String msg) {
        if (!java.util.Objects.equals(exp, act)) throw new AssertionError(msg + " (expected " + exp + ", actual " + act + ")");
    }
    private static <T> T timeout(CompletableFuture<T> f) {
        try { return f.orTimeout(120, java.util.concurrent.TimeUnit.SECONDS).join(); }
        catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() != null) throw new RuntimeException(e.getCause());
            throw e;
        }
    }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
