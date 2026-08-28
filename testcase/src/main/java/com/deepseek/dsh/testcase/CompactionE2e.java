package com.deepseek.dsh.testcase;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.deepseek.dsh.sdk.client.HarnessClient;

/**
 * 长消息会话上下文压缩端到端验证。
 *
 * <p>驱动一个多轮长会话（每轮注入一段需记忆的文本，构建长上下文），随后触发
 * {@code session/compact} 压缩，验证：
 * <ul>
 *   <li>压缩前消息数 &gt; keepRecent（确实构成「长会话」）；</li>
 *   <li>压缩后消息数减少（before &gt; after，旧消息折叠为摘要）；</li>
 *   <li>压缩后会话仍可继续对话（不破坏会话状态）。</li>
 * </ul>
 * 走 RPC（start-rpc）子进程，agent 与 web chat 同源（BaseBundle）。
 *
 * <p>环境变量：DSH_RPC_CMD（start-rpc 路径）、DEEPSEEK_API_KEY / DSH_MODEL / DSH_BASE_URL、
 * DSH_DATA_DIR（临时数据目录）。
 */
public final class CompactionE2e {

    private static final AtomicInteger passed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);
    private static final int TURNS = 8; // 8 轮 → 16 条消息 > keepRecent(8)，触发压缩

    public static void main(String[] args) {
        String rpcCmd = System.getenv("DSH_RPC_CMD");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        String model = System.getenv("DSH_MODEL");
        if (rpcCmd == null || rpcCmd.isBlank()) { System.err.println("[compaction-e2e] 未设置 DSH_RPC_CMD，跳过"); return; }
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            System.err.println("[compaction-e2e] 未设置 DEEPSEEK_API_KEY/DSH_MODEL，跳过"); return;
        }

        System.out.println("[compaction-e2e] 启动 RPC 子进程: " + rpcCmd);
        try (HarnessClient client = new HarnessClient(rpcCmd)) {
            String sid = client.createSession().join();
            group("长消息会话上下文压缩");

            check("驱动 " + TURNS + " 轮构建长会话（每轮记忆一段文本）", () -> {
                for (int i = 1; i <= TURNS; i++) {
                    // 每轮注入一段较长的需记忆文本，构建长上下文
                    String msg = "请记住第" + i + "轮信息：项目代号 P-" + i
                            + "，关键参数 value" + i + "=密钥串" + i + "。请用一句话简短确认你记住了。";
                    var r = timeout(client.prompt(sid, msg));
                    assertTrue(r.reply() != null && !r.reply().isBlank(),
                            "第" + i + "轮应有回复，实际: " + truncate(r.reply(), 80));
                }
            });

            check("压缩前消息数 > keepRecent（构成长会话）", () -> {
                var h = client.history(sid).join();
                assertNull(h.error(), "history 不应有错误");
                assertTrue(h.messages().size() > 8,
                        "长会话消息数应 > keepRecent(8)，实际 " + h.messages().size());
            });

            check("上下文触发压缩 (session/compact) → before > after", () -> {
                var c = client.compactSession(sid, 2048).join();
                assertNull(c.error(), "compact 不应有错误");
                int before = c.before();
                int after = c.after();
                System.out.println("    compact: before=" + before + " after=" + after);
                assertTrue(before > 8, "压缩前消息数应 > 8，实际 " + before);
                assertTrue(after < before, "压缩后消息数应减少 (after<before)，" + after + " < " + before);
            });

            check("压缩后会话仍可继续对话", () -> {
                var r = timeout(client.prompt(sid, "用一句话告诉我：前面你记住了几轮信息？"));
                assertTrue(r.reply() != null && !r.reply().isBlank(),
                        "压缩后应仍能回复，实际: " + truncate(r.reply(), 80));
            });

            check("shutdown", () -> client.shutdown().join());
            System.out.println();
            System.out.println("[compaction-e2e] Result: " + passed.get() + " passed, " + failed.get() + " failed");
            if (failed.get() > 0) System.exit(1);
        } catch (Exception e) {
            System.err.println("[compaction-e2e] Exception: " + e);
            e.printStackTrace();
            System.exit(1);
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
