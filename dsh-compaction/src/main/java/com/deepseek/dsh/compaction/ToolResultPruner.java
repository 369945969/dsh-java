package com.deepseek.dsh.compaction;

import java.util.ArrayList;
import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 工具结果裁剪器 —— 对应原 Harness 的 {@code compaction-tool-result-pruner}。
 *
 * <p>策略：当消息列表超限时，优先裁剪/截断过长的 tool 结果消息，
 * 因为工具输出通常体积最大且可重新获取。
 *
 * <p>设计模式：策略（与摘要型压缩互补，可组合使用）。
 */
public final class ToolResultPruner {

    /** 单条工具结果的最大字符数，超出则截断。 */
    private final int maxResultChars;
    /** 裁剪后保留的尾部字符数（保留末尾的错误信息）。 */
    private final int tailKeep;

    public ToolResultPruner() {
        this(4000, 800);
    }

    public ToolResultPruner(int maxResultChars, int tailKeep) {
        this.maxResultChars = maxResultChars;
        this.tailKeep = tailKeep;
    }

    /**
     * 裁剪过长的 tool 角色消息内容。
     */
    public List<ChatMessage> prune(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.role() == ChatMessage.Role.TOOL
                    && m.content() != null && m.content().length() > maxResultChars) {
                String trimmed = truncateMiddle(m.content());
                result.add(new ChatMessage(m.role(), trimmed, m.toolCallId(), m.toolCalls()));
            } else {
                result.add(m);
            }
        }
        return result;
    }

    /** 中间截断：保留头部与尾部，中间用省略号替代。 */
    private String truncateMiddle(String text) {
        int head = maxResultChars - tailKeep - 20;
        return text.substring(0, head)
                + "\n…[已截断 " + (text.length() - maxResultChars + tailKeep) + " 字符]…\n"
                + text.substring(text.length() - tailKeep);
    }
}
