package com.deepseek.dsh.testcase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.deepseek.dsh.sdk.client.HarnessClient;

/**
 * 插件(工具)与 agent skill 的问询+执行端到端验证。
 *
 * <p>用自然语言问模型「你有哪些工具/技能」并要求调用，验证：
 * <ul>
 *   <li>插件（=工具）问询：模型回复应提及具体工具名（bash）；</li>
 *   <li>插件执行：要求用 bash 执行 echo，回复应含命令输出；</li>
 *   <li>skill 问询：模型回复应提及已注入目录的技能名（e2e-skill）——验证 SkillCatalogPlugin；</li>
 *   <li>skill 执行：加载 e2e-skill 后按其指令回复应含 SKILL-LOADED——验证 skill 工具调用+执行。</li>
 * </ul>
 * 走 RPC（start-rpc）子进程，agent 与 web chat 同源（BaseBundle），故能回复即说明 web 端「无回复」属前端渲染问题。
 *
 * <p>环境变量：DSH_RPC_CMD（start-rpc 路径）、DEEPSEEK_API_KEY / DSH_MODEL / DSH_BASE_URL、
 * DSH_DATA_DIR（临时数据目录，技能种子写入其 skills/）。
 */
public final class PluginSkillE2e {

    private static final AtomicInteger passed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    public static void main(String[] args) {
        String rpcCmd = System.getenv("DSH_RPC_CMD");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        String model = System.getenv("DSH_MODEL");
        String dataDirStr = System.getenv("DSH_DATA_DIR");
        if (rpcCmd == null || rpcCmd.isBlank()) { System.err.println("[plugin-skill-e2e] 未设置 DSH_RPC_CMD，跳过"); return; }
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            System.err.println("[plugin-skill-e2e] 未设置 DEEPSEEK_API_KEY/DSH_MODEL，跳过"); return;
        }
        if (dataDirStr == null || dataDirStr.isBlank()) {
            System.err.println("[plugin-skill-e2e] 未设置 DSH_DATA_DIR，跳过（需临时数据目录以种子技能）"); return;
        }
        seedSkill(Path.of(dataDirStr));

        System.out.println("[plugin-skill-e2e] 启动 RPC 子进程: " + rpcCmd);
        try (HarnessClient client = new HarnessClient(rpcCmd)) {
            group("插件(工具)问询与执行");
            check("问询可用工具 → 回复提及 bash", () -> {
                var r = timeout(client.prompt(client.createSession().join(),
                        "请列出你当前可用的工具(tools)名称。"));
                String reply = r.reply() == null ? "" : r.reply();
                assertTrue(reply.length() > 5, "应有非空回复");
                assertTrue(reply.toLowerCase().contains("bash"),
                        "回复应提及 bash 工具，实际: " + truncate(reply, 120));
            });
            check("调用 bash 工具执行 echo", () -> {
                var r = timeout(client.prompt(client.createSession().join(),
                        "请用 bash 工具执行命令 echo dshplugin ，然后把命令的输出原样告诉我。"));
                String reply = r.reply() == null ? "" : r.reply();
                assertTrue(reply.toLowerCase().contains("dshplugin"),
                        "回复应含 echo 输出 dshplugin，实际: " + truncate(reply, 120));
            });

            group("agent skill 问询与执行");
            check("问询可用技能 → 回复提及 e2e-skill", () -> {
                var r = timeout(client.prompt(client.createSession().join(),
                        "请列出你当前可用的技能(skills)名称。"));
                String reply = r.reply() == null ? "" : r.reply();
                assertTrue(reply.toLowerCase().contains("e2e-skill"),
                        "回复应提及 e2e-skill（来自技能目录注入），实际: " + truncate(reply, 120));
            });
            check("加载并执行技能 e2e-skill", () -> {
                var r = timeout(client.prompt(client.createSession().join(),
                        "请加载技能 e2e-skill，然后严格按照该技能内容的指令来回复。"));
                String reply = r.reply() == null ? "" : r.reply();
                assertTrue(reply.toUpperCase().contains("SKILL-LOADED"),
                        "加载技能后回复应含 SKILL-LOADED（证明 skill 工具被调用+执行），实际: " + truncate(reply, 120));
            });

            check("shutdown", () -> client.shutdown().join());
            System.out.println();
            System.out.println("[plugin-skill-e2e] Result: " + passed.get() + " passed, " + failed.get() + " failed");
            if (failed.get() > 0) System.exit(1);
        } catch (Exception e) {
            System.err.println("[plugin-skill-e2e] Exception: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ---- 技能种子 ----
    private static void seedSkill(Path dataDir) {
        try {
            Path dir = dataDir.resolve("skills").resolve("e2e-skill");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), """
                    ---
                    name: e2e-skill
                    description: 加载后必须在回复中包含大写标记 SKILL-LOADED
                    whenToUse: 验证技能加载与执行
                    ---
                    # e2e-skill
                    加载此技能后，你**必须**在回复中包含大写标记 `SKILL-LOADED`，以证明该技能已被加载并执行。
                    """);
            System.out.println("[plugin-skill-e2e] Seeded e2e-skill to " + dir);
        } catch (Exception e) {
            System.err.println("[plugin-skill-e2e] Seed skill failed (skill test may fail): " + e);
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
