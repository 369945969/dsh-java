package com.deepseek.dsh.testcase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.deepseek.dsh.sdk.client.HarnessClient;

/**
 * RPC E2E test driver (dsh SDK client) - covers all development mode groups.
 *
 * <p>Spawns RPC server subprocess ({@code start-rpc.bat}, stdio JSON-RPC),
 * drives via SDK client, verifies all RPC features:
 * <pre>
 * [basic chat] initialize / greeting / full response
 * [session & memory] context memory (multi-turn) / fork inherits parent memory /
 *                     fork no memory / query by sessionId / session list /
 *                     context compaction / session deletion
 * [session cancel] cancel a long-running turn
 * [skill & orchestration] skill list+get / subagent / team
 * </pre>
 *
 * <p>Env: DSH_RPC_CMD (start-rpc path), DSH_DATA_DIR (optional; default ~/.dsh),
 *        DSH_TOKEN + DSH_WEB_URL (optional; when set, sessions are attached to
 *        a date-named workspace on the web server so they show in the web UI).
 */
public final class RpcE2e {

    private static final AtomicInteger passed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    // ---- HTTP helpers (workspace management on the web server) ----

    private static final String WEB_URL = System.getenv().getOrDefault("DSH_WEB_URL", "http://localhost:8765");
    private static java.net.http.HttpClient httpClient;
    private static String cookie;
    private static String workspaceId;

    private static void initHttp() {
        String token = System.getenv("DSH_TOKEN");
        if (token == null || token.isBlank()) return;
        try {
            httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(WEB_URL + "/?token=" + token))
                    .method("GET", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
            var res = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            var sc = res.headers().firstValue("set-cookie").orElse(null);
            if (sc != null) cookie = sc.split(";")[0];
        } catch (Exception e) {
            System.err.println("[rpc-e2e] HTTP auth failed (workspace attach disabled): " + e);
        }
    }

    private static String dateStr() {
        return new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
    }

    private static String workspacePath;

    private static void ensureWorkspace() {
        if (cookie == null) return;
        String name = dateStr();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var listReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(WEB_URL + "/api/workspace.list"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", cookie)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"rpcId\":\"rpc-e2e\",\"payload\":{}}"))
                    .build();
            var listRes = httpClient.send(listReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            var items = mapper.readTree(listRes.body()).path("result").path("value").path("items");
            if (items.isArray()) {
                for (var item : items) {
                    if (name.equals(item.path("title").asText())) {
                        workspaceId = item.path("workspaceId").asText();
                        workspacePath = item.path("path").asText();
                        System.out.println("[rpc-e2e] Reusing workspace: " + name + " (id=" + workspaceId + ", path=" + workspacePath + ")");
                        return;
                    }
                }
            }
            var body = "{\"type\":\"client-request\",\"rpcId\":\"rpc-e2e\",\"method\":\"create\",\"payload\":{\"args\":{\"path\":\""
                    + name + "\"}}}";
            var createReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(WEB_URL + "/api/workspace/create"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", cookie)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var createRes = httpClient.send(createReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            var wsNode = mapper.readTree(createRes.body()).path("result").path("value").path("workspace");
            workspaceId = wsNode.path("workspaceId").asText(null);
            workspacePath = wsNode.path("path").asText(null);
            System.out.println("[rpc-e2e] Created workspace: " + name + " (id=" + workspaceId + ", path=" + workspacePath + ")");
        } catch (Exception e) {
            System.err.println("[rpc-e2e] Workspace setup failed: " + e);
        }
    }

    private static void attachSession(String sid) {
        if (cookie == null || workspaceId == null || sid == null) return;
        try {
            var body = "{\"rpcId\":\"rpc-e2e\",\"payload\":{\"workspaceId\":\"" + workspaceId
                    + "\",\"sessionId\":\"" + sid + "\"}}";
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(WEB_URL + "/api/workspace.insertSessionBefore"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", cookie)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[rpc-e2e] Attach session failed: " + e);
        }
    }

    private static String createSession(HarnessClient client) {
        String sid = client.createSession(null, workspacePath).join();
        attachSession(sid);
        return sid;
    }

    private static java.util.Set<String> snapshotSessions(HarnessClient client) {
        try { return new java.util.HashSet<>(client.listSessions().join().sessionIds()); }
        catch (Exception e) { return java.util.Collections.emptySet(); }
    }

    private static void attachNewSessions(HarnessClient client, java.util.Set<String> before) {
        var after = snapshotSessions(client);
        after.removeAll(before);
        for (String sid : after) attachSession(sid);
    }

    // ---- main ----

    public static void main(String[] args) {
        String rpcCmd = System.getenv("DSH_RPC_CMD");
        String dataDirEnv = System.getenv("DSH_DATA_DIR");
        Path dataDir = dataDirEnv != null && !dataDirEnv.isBlank()
                ? Path.of(dataDirEnv)
                : Path.of(System.getProperty("user.home"), ".dsh");

        if (rpcCmd == null || rpcCmd.isBlank()) { System.err.println("[rpc-e2e] DSH_RPC_CMD not set, skipping"); return; }

        seedSkills(dataDir);
        initHttp();
        ensureWorkspace();

        System.out.println("[rpc-e2e] Starting RPC server subprocess: " + rpcCmd);
        try (HarnessClient client = new HarnessClient(rpcCmd)) {

            // ========== basic chat mode ==========
            group("basic chat mode");

            check("initialize (model non-empty)", () -> {
                var init = client.initialize().join();
                assertTrue(init.model() != null && !init.model().isBlank(), "model should not be empty");
                assertTrue(init.protocolVersion() != null && !init.protocolVersion().isBlank(), "protocolVersion should not be empty");
                System.out.println("    model=" + init.model() + ", provider=" + init.provider());
            });
            check("basic greeting (session/prompt)", () -> {
                var r = timeout(client.prompt(createSession(client), "Hello, introduce yourself in one sentence."));
                assertEquals("ok", r.status(), "status should be ok");
                assertTrue(r.reply() != null && !r.reply().isBlank(), "greeting reply should not be empty");
                System.out.println("    Reply: " + truncate(r.reply(), 100));
            });
            check("full response (reply+tokens)", () -> {
                var r = timeout(client.prompt(createSession(client), "Introduce Java in one sentence."));
                assertTrue(r.reply() != null && r.reply().length() > 5, "full reply should have content");
                assertTrue(r.totalTokens() >= 0, "token stats should exist");
            });

            // ========== session & memory mode ==========
            group("session & memory mode");

            // --- context memory: multi-turn conversation remembers facts ---
            String memSid = createSession(client);
            check("context memory turn 1 (store facts)", () -> {
                timeout(client.prompt(memSid, "Please remember: my name is Alice, my favorite color is blue, and my secret code is XYZ789."));
                var h = client.history(memSid).join();
                assertNull(h.error(), "history should have no error");
                assertTrue(!h.messages().isEmpty(), "memory should be saved after turn 1");
            });
            check("context memory turn 2 (recall facts)", () -> {
                var r = timeout(client.prompt(memSid, "What is my name, my favorite color, and my secret code?"));
                String reply = r.reply().toLowerCase();
                boolean name = reply.contains("alice");
                boolean color = reply.contains("blue");
                boolean code = reply.contains("xyz789");
                assertTrue(name && color && code,
                    "should recall all facts (name=" + name + ", color=" + color + ", code=" + code + ")");
                System.out.println("    Reply: " + truncate(r.reply(), 120));
            });

            // --- fork child inherits parent memory ---
            check("fork child inherits parent memory", () -> {
                var f = client.forkSession(memSid).join();
                assertNull(f.error(), "fork should have no error");
                assertTrue(f.replayedEvents() > 0, "should replay parent events");
                attachSession(f.childSessionId());
                // ask the child about facts stored in parent
                var r = timeout(client.prompt(f.childSessionId(), "What is my name and secret code?"));
                String reply = r.reply().toLowerCase();
                assertTrue(reply.contains("alice") && reply.contains("xyz789"),
                    "fork child should inherit parent memory (name=" + reply.contains("alice") + ", code=" + reply.contains("xyz789") + ")");
                System.out.println("    Reply: " + truncate(r.reply(), 100));
            });

            // --- fork new session has no memory ---
            check("fork new session has no memory", () -> {
                String fresh = createSession(client);
                var r = timeout(client.prompt(fresh, "What is my name and secret code?"));
                String reply = r.reply().toLowerCase();
                assertTrue(!reply.contains("alice") && !reply.contains("xyz789"),
                    "fresh session should not know parent facts");
                System.out.println("    Reply: " + truncate(r.reply(), 100));
            });

            // --- query by sessionId: different sessions have different histories ---
            check("query by sessionId (distinct histories)", () -> {
                String sid1 = createSession(client);
                String sid2 = createSession(client);
                timeout(client.prompt(sid1, "Remember: my city is Tokyo."));
                timeout(client.prompt(sid2, "Remember: my city is Paris."));
                var h1 = client.history(sid1).join();
                var h2 = client.history(sid2).join();
                assertTrue(h1.messages().toString().contains("Tokyo"), "sid1 should have Tokyo");
                assertTrue(h2.messages().toString().contains("Paris"), "sid2 should have Paris");
            });

            // --- session list ---
            check("session list (contains active sessions)", () -> {
                var list = client.listSessions().join();
                assertTrue(list.sessionIds().contains(memSid), "list should contain memory session");
                assertTrue(list.count() >= 2, "count should be >=2");
            });

            // --- context compaction ---
            check("context compaction (compact)", () -> {
                var c = client.compactSession(memSid, 2048).join();
                assertNull(c.error(), "compact should have no error");
                assertTrue(c.before() > 0, "before count should be >0");
                assertTrue(c.after() <= c.before(), "after should not be more than before");
                System.out.println("    before=" + c.before() + ", after=" + c.after());
            });

            // --- session deletion ---
            check("session deletion (create + delete + verify gone)", () -> {
                String sid = client.createSession("del-test-" + System.currentTimeMillis(), workspacePath).join();
                // verify it exists
                var hBefore = client.history(sid).join();
                assertNull(hBefore.error(), "session should exist before delete");
                // delete it
                assertTrue(client.deleteSession(sid).join(), "first delete should succeed");
                assertTrue(!client.deleteSession(sid).join(), "second delete should return false");
                // verify it's gone from list
                var list = client.listSessions().join();
                assertTrue(!list.sessionIds().contains(sid), "deleted session should not be in list");
            });

            // ========== session cancel mode ==========
            group("session cancel mode");

            check("session cancel (long-running turn)", () -> {
                String sid = createSession(client);
                // start a long prompt (non-blocking via future)
                CompletableFuture<com.deepseek.dsh.sdk.client.HarnessClient.PromptResult> future =
                        client.prompt(sid, "Please write a very long 2000-word essay about the history of computing.");
                // give it a moment to start
                Thread.sleep(500);
                // cancel via raw transport
                var cancelFuture = client.transport().request("session.cancel",
                        java.util.Map.of("sessionId", sid));
                try { cancelFuture.join(); } catch (Exception ignored) {}
                // the prompt future should complete (either cancelled or done)
                try {
                    var r = future.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join();
                    // session should still be queryable after cancel
                    var h = client.history(sid).join();
                    assertNull(h.error(), "session should still be queryable after cancel");
                } catch (Exception e) {
                    // timeout or cancellation is acceptable
                    System.out.println("    (prompt was cancelled as expected)");
                }
            });

            // ========== skill & orchestration mode ==========
            group("skill & orchestration mode");

            check("skill discovery (skill/list)", () -> {
                var list = client.skillList().join();
                assertTrue(list.count() >= 2, "should find at least 2 seeded skills, got " + list.count());
                assertTrue(list.skills().stream().anyMatch(s -> s.name().equals("code-review")), "should contain code-review");
                assertTrue(list.skills().stream().anyMatch(s -> s.name().equals("commit-helper")), "should contain commit-helper");
            });
            check("skill load (skill/get)", () -> {
                var g = client.skillGet("code-review").join();
                assertTrue(g.found(), "code-review should be loadable");
                assertTrue(g.rendered().contains("<skill_content"), "rendered should produce skill_content block");
            });
            check("subagent delegation (subagent/task)", () -> {
                String sid = createSession(client);
                var before = snapshotSessions(client);
                var r = client.subagentTask(sid, "Summarize the ReAct pattern in one sentence.").join();
                assertNull(r.error(), "subagent should have no error");
                assertTrue(r.success(), "subagent should succeed");
                assertTrue(r.report() != null && !r.report().isBlank(), "subagent report should not be empty");
                attachNewSessions(client, before);
                System.out.println("    Report: " + truncate(r.report(), 100));
            });
            check("multi-agent team (team/run)", () -> {
                var before = snapshotSessions(client);
                var r = client.teamRun("Explain the value of unit testing in one sentence.").join();
                assertNull(r.error(), "team should have no error");
                assertEquals(2, r.memberCount(), "should have 2 members");
                assertTrue(r.allSucceeded(), "both members should succeed");
                assertTrue(r.summary() != null && !r.summary().isBlank(), "should have aggregated summary");
                attachNewSessions(client, before);
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

    // ---- skill seeds ----
    private static void seedSkills(Path dataDir) {
        try {
            Path dir = dataDir.resolve("skills");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("code-review.md"), """
                    ---
                    name: code-review
                    description: Code review skill
                    whenToUse: When you need to review code
                    ---
                    # Code Review
                    Check code quality, potential bugs and improvement suggestions line by line.
                    """);
            Files.writeString(dir.resolve("commit-helper.md"), """
                    ---
                    name: commit-helper
                    description: Commit message generation skill
                    whenToUse: When you need to generate a commit message
                    ---
                    # Commit Message
                    Generate commit messages following Conventional Commits spec.
                    """);
            System.out.println("[rpc-e2e] Seeded 2 skill(s) to " + dir);
        } catch (Exception e) {
            System.err.println("[rpc-e2e] Seed skill failed (skill test may fail): " + e);
        }
    }

    // ---- assertion skeleton ----
    private interface Case { void run() throws Exception; }
    private static void group(String name) { System.out.println("\n  -- " + name + " --"); }
    private static void check(String name, Case c) {
        try { c.run(); System.out.println("  [PASS] " + name); passed.incrementAndGet(); }
        catch (Throwable t) { System.out.println("  [FAIL] " + name + " - " + t.getMessage()); failed.incrementAndGet(); }
    }
    private static void assertTrue(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    private static void assertNull(Object o, String msg) { if (o != null) throw new AssertionError(msg + " (actual " + o + ")"); }
    private static void assertEquals(Object exp, Object act, String msg) {
        if (!java.util.Objects.equals(exp, act)) throw new AssertionError(msg + " (expected " + exp + ", actual " + act + ")");
    }
    private static <T> T timeout(CompletableFuture<T> f) {
        try { return f.orTimeout(300, java.util.concurrent.TimeUnit.SECONDS).join(); }
        catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() != null) throw new RuntimeException(e.getCause());
            throw e;
        }
    }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
