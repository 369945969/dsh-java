package com.deepseek.dsh.compaction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.session.log.ChatMessage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础压缩提供者测试 —— needsCompaction 阈值 + compact 保留近期 + 摘要。
 */
class BasicCompactionProviderTest {

    @Test
    void needsCompaction按token比例判定() {
        var p = new BasicCompactionProvider();
        assertTrue(p.needsCompaction(List.of(), 9000, 10000));   // 90% > 80%
        assertFalse(p.needsCompaction(List.of(), 1000, 10000));  // 10%
    }

    @Test
    void 少于keepRecent不压缩() {
        var p = new BasicCompactionProvider(8, 0.8);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("hi"));
        msgs.add(ChatMessage.assistant("hello", List.of()));
        var out = p.compact(msgs, 1000);
        assertSame(msgs, out, "消息数 <= keepRecent 时原样返回");
    }

    @Test
    void 压缩保留系统提示与近期消息并生成摘要() {
        var p = new BasicCompactionProvider(2, 0.8);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system("你是助手"));
        msgs.add(ChatMessage.user("早期1"));
        msgs.add(ChatMessage.assistant("回复1", List.of()));
        msgs.add(ChatMessage.user("早期2"));
        msgs.add(ChatMessage.assistant("近期1", List.of()));  // keepRecent
        msgs.add(ChatMessage.user("近期2"));                  // keepRecent
        var out = p.compact(msgs, 1000);
        assertTrue(out.size() < msgs.size(), "压缩后条数应减少");
        // 首条仍为系统提示，第二条为摘要
        assertEquals(ChatMessage.Role.SYSTEM, out.get(0).role());
        assertTrue(out.get(1).content().startsWith("[对话摘要]"));
        // 末尾保留近期 2 条
        assertEquals("近期1", out.get(out.size() - 2).content());
        assertEquals("近期2", out.get(out.size() - 1).content());
    }
}
