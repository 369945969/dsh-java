package com.deepseek.dsh.testcase;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.deepseek.dsh.sdk.client.HarnessClient;

/**
 * RPC 端到端验证驱动 —— 基于 dsh SDK 客户端（{@link HarnessClient}）。
 *
 * <p>以子进程方式启动后端 RPC 服务端（{@code scripts/start-rpc.sh}，stdio
 * newline-delimited JSON-RPC 2.0），通过 SDK 客户端外部访问，逐项验证后端
 * RPC 提供的全部功能：{@code initialize} / {@code health} / {@code session.create}
 * / {@code session.list} / {@code session/prompt}（真实模型调用）/
 * {@code session.history} / {@code session.delete} / {@code shutdown}。
 *
 * <p>运行：{@code mvn -pl testcase exec:java -Dexec.mainClass=...RpcE2e}
 * 环境变量：{@code DSH_RPC_CMD}（start-rpc.sh 路径）、{@code DEEPSEEK_API_KEY}
 * / {@code DSH_BASE_URL} / {@code DSH_MODEL}（由 start-rpc.sh 读取）。
 *
 * <p>设计模式：集成测试驱动 + 远程代理（SDK 客户端验证服务端契约）。
 */
public final class RpcE2e {

    private static final AtomicInteger passed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    public static void main(String[] args) {
        String rpcCmd = System.getenv("DSH_RPC_CMD");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        String model = System.getenv("DSH_MODEL");

        if (rpcCmd == null || rpcCmd.isBlank()) {
            System.err.println("[rpc-e2e] 未设置 DSH_RPC_CMD（start-rpc.sh 路径），跳过");
            return;
        }
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
            System.err.println("[rpc-e2e] 未设置 DEEPSEEK_API_KEY/DSH_MODEL（运行 testcase/run-all.sh 注入），跳过");
            return;
        }

        System.out.println("[rpc-e2e] 启动 RPC 服务端子进程: " + rpcCmd);
        try (HarnessClient client = new HarnessClient(rpcCmd)) {
            check("initialize", () -> {
                var init = client.initialize().join();
                assertTrue(model.equals(init.model()), "model 应为 " + model + "，实际 " + init.model());
                assertTrue(init.protocolVersion() != null && !init.protocolVersion().isBlank(),
                        "protocolVersion 不应为空");
            });

            check("health", () -> {
                var h = client.health().join();
                assertTrue("ok".equals(h.status()), "health.status 应为 ok，实际 " + h.status());
            });

            String sid = client.createSession().join();
            check("session.create", () -> assertTrue(sid != null && !sid.isBlank(), "sessionId 不应为空"));

            check("session.list", () -> {
                var list = client.listSessions().join();
                assertTrue(list.sessionIds().contains(sid), "session.list 应包含创建的 sid");
            });

            check("session/prompt (真实模型)", () -> {
                var r = timeout(client.prompt(sid, "你好，请用一句话介绍你自己。"));
                assertEquals(sid, r.sessionId(), "sessionId 应一致");
                assertEquals("ok", r.status(), "status 应为 ok");
                assertTrue(r.reply() != null && !r.reply().isBlank(), "回复不应为空");
                System.out.println("  [glm 回复] " + truncate(r.reply(), 120));
            });

            check("session.history", () -> {
                var hist = client.history(sid).join();
                assertTrue(hist.error() == null, "history 不应有错误");
                assertTrue(!hist.messages().isEmpty(), "历史消息不应为空");
            });

            check("session.delete", () -> {
                String d = client.createSession("to-delete").join();
                assertEquals("to-delete", d, "应使用指定 sid");
                assertTrue(client.deleteSession("to-delete").join(), "首次删除应成功");
                assertTrue(!client.deleteSession("to-delete").join(), "二次删除应返回 false");
            });

            check("shutdown", () -> {
                client.shutdown().join();
            });

            System.out.println();
            System.out.println("[rpc-e2e] 结果: " + passed.get() + " 通过, " + failed.get() + " 失败");
            if (failed.get() > 0) System.exit(1);
        } catch (Exception e) {
            System.err.println("[rpc-e2e] 异常: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ---- 断言与用例运行骨架 ----

    private interface Case {
        void run() throws Exception;
    }

    private static void check(String name, Case c) {
        try {
            c.run();
            System.out.println("  [PASS] " + name);
            passed.incrementAndGet();
        } catch (Throwable t) {
            System.out.println("  [FAIL] " + name + " — " + t.getMessage());
            failed.incrementAndGet();
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    private static <T> T timeout(CompletableFuture<T> f) {
        try {
            return f.orTimeout(90, java.util.concurrent.TimeUnit.SECONDS).join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() != null) throw new RuntimeException(e.getCause());
            throw e;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
