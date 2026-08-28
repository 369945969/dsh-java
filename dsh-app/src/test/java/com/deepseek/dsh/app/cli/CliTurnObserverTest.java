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
        String out = render(o -> o.onAssistantMessage("final reply", "step one\nstep two"));
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
        String out = render(o -> o.onAssistantMessage("just text", null));
        assertTrue(out.contains("just text"));
        assertFalse(out.contains("think"), "no think block when reasoning absent");
    }

    @Test
    void emptyMessagePrintsNothing() {
        assertEquals("", render(o -> o.onAssistantMessage("", "")));
        assertEquals("", render(o -> o.onAssistantMessage(null, null)));
    }

    @Test
    void toolCallAndResultCollapsed() {
        String out = render(o -> {
            o.onToolCall("c1", "read", "{\"path\":\"/a\"}");
            o.onToolResult("c1", "file contents\nline2");
        });
        assertTrue(out.contains("> read"), "tool-call line with name");
        assertTrue(out.contains("{\"path\":\"/a\"}"), "tool arguments present");
        assertTrue(out.contains("-> "), "tool-result prefix");
        assertTrue(out.contains("file contents line2"), "result newlines collapsed to spaces");
    }

    @Test
    void truncatesLongText() {
        String args = "x".repeat(500);
        String out = render(o -> o.onToolCall("c", "t", args));
        assertTrue(out.contains("..."), "truncated marker");
        assertFalse(out.contains("x".repeat(300)), "long run of xs not shown in full");
    }

    @Test
    void streamsReasoningThenContentByChunk() {
        String out = render(o -> {
            o.onAssistantChunk(null, "think-1");   // reasoning delta -> opens think block
            o.onAssistantChunk("ans", null);       // content delta -> closes think, streams content
            o.onAssistantMessage("ans", "think-1"); // step end: streamed -> only trailing newline
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
            o.onAssistantMessage("first", null);
            o.onAssistantMessage("second", "why");   // no chunks this step -> fallback
        });
        assertTrue(out.contains("first"));
        assertTrue(out.contains("second"));
        assertTrue(out.contains("why"), "fallback think block on second (non-streamed) step");
    }
}
