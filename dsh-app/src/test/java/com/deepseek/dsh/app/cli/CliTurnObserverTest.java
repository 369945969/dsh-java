package com.deepseek.dsh.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * {@link CliTurnObserver} 单测 —— 验证 think/正文/工具调用/结果的终端渲染格式
 * 与顺序，无需模型或 agent（直接喂事件、捕获 PrintStream）。
 */
class CliTurnObserverTest {

    private static String render(Consumer<CliTurnObserver> body) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CliTurnObserver o = new CliTurnObserver(new PrintStream(buf, true, StandardCharsets.UTF_8));
        body.accept(o);
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rendersThinkBlockThenContent() {
        String out = render(o -> o.onAssistantMessage("final reply", "step one\nstep two", "a-test"));
        int thinkIdx = out.indexOf("-- think");
        int contentIdx = out.indexOf("final reply");
        assertTrue(thinkIdx >= 0, "think header present");
        assertTrue(contentIdx >= 0, "content present");
        assertTrue(thinkIdx < contentIdx, "think block precedes content");
        assertTrue(out.contains("  step one"), "reasoning line 1 indented");
        assertTrue(out.contains("  step two"), "reasoning line 2 indented");
    }

    @Test
    void contentOnlyWhenNoReasoning() {
        String out = render(o -> o.onAssistantMessage("just text", null, "a-test"));
        assertTrue(out.contains("just text"));
        assertFalse(out.contains("think"), "no think block when reasoning absent");
    }

    @Test
    void emptyMessagePrintsNothing() {
        assertEquals("", render(o -> o.onAssistantMessage("", "", "a-test")));
        assertEquals("", render(o -> o.onAssistantMessage(null, null, "a-test")));
    }

    @Test
    void toolCallAndResultCollapsed() {
        String out = render(o -> {
            o.onToolCall("c1", "read", "{\"path\":\"/a/b.md\"}");
            o.onToolResult("c1", "file contents\nline2");
        });
        assertTrue(out.contains("> read: /a/b.md"), "tool-call shows name + path");
        assertFalse(out.contains("file contents"), "result content not printed");
        assertTrue(out.contains("✓"), "tool-result checkmark");
    }

    @Test
    void toolCallShowsBashCommand() {
        String out = render(o -> o.onToolCall("c", "bash", "{\"command\":\"ls -la /tmp\"}"));
        assertTrue(out.contains("> bash: ls -la /tmp"), "bash command shown");
    }

    @Test
    void toolCallShowsGrepPattern() {
        String out = render(o -> o.onToolCall("c", "grep", "{\"pattern\":\"TODO\",\"path\":\"src\"}"));
        assertTrue(out.contains("> grep: TODO"), "grep pattern shown");
    }

    @Test
    void truncatesLongText() {
        String args = "{\"command\":\"" + "x".repeat(500) + "\"}";
        String out = render(o -> o.onToolCall("c", "bash", args));
        assertTrue(out.contains("> bash:"), "tool name shown");
        assertFalse(out.contains("x".repeat(300)), "long command truncated");
    }

    @Test
    void streamsReasoningThenContentByChunk() {
        String out = render(o -> {
            o.onAssistantChunk(null, "think-1");   // reasoning delta -> opens think block
            o.onAssistantChunk("ans", null);       // content delta -> closes think, streams content
            o.onAssistantMessage("ans", "think-1", "a-test"); // step end: streamed -> only trailing newline
        });
        assertTrue(out.contains("-- think ----------------"), "think header on first reasoning chunk");
        assertTrue(out.contains("think-1"), "reasoning streamed");
        assertTrue(out.contains("ans"), "content streamed");
        assertTrue(out.contains("------------------------"), "think footer when content begins");
        assertTrue(out.indexOf("ans") == out.lastIndexOf("ans"),
                "content printed once (not duplicated by onAssistantMessage)");
    }

    @Test
    void streamingThenFallbackResetsAcrossSteps() {
        // step 1 streamed, step 2 non-streamed (fallback) — state resets between
        String out = render(o -> {
            o.onAssistantChunk("first", null);
            o.onAssistantMessage("first", null, "a-test");
            o.onAssistantMessage("second", "why", "a-test");   // no chunks this step -> fallback
        });
        assertTrue(out.contains("first"));
        assertTrue(out.contains("second"));
        assertTrue(out.contains("why"), "fallback think block on second (non-streamed) step");
    }
}
