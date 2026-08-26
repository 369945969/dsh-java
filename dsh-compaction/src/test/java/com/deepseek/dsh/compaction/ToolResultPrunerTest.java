package com.deepseek.dsh.compaction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.session.log.ChatMessage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文压缩测试 —— 工具结果裁剪器。
 */
class ToolResultPrunerTest {

    @Test
    void 裁剪过长的工具结果() {
        var pruner = new ToolResultPruner(100, 20);
        String longText = "x".repeat(500);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("问题"));
        messages.add(new ChatMessage(ChatMessage.Role.TOOL, longText, "call-1", List.of()));

        var result = pruner.prune(messages);
        // user 消息不变
        assertEquals("问题", result.get(0).content());
        // tool 消息被截断
        assertTrue(result.get(1).content().length() < longText.length());
        assertTrue(result.get(1).content().contains("已截断"));
    }

    @Test
    void 短结果不被裁剪() {
        var pruner = new ToolResultPruner(1000, 100);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessage.Role.TOOL, "短结果", "call-1", List.of()));

        var result = pruner.prune(messages);
        assertEquals("短结果", result.get(0).content());
    }
}
