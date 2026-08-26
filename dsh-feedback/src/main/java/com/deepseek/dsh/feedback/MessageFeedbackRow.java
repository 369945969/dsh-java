package com.deepseek.dsh.feedback;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息反馈侧车行 —— 对应原 Harness 的 {@code MessageFeedbackRow}。
 *
 * <p>每个会话 ID 对应一行整会话侧车记录：会话生命周期标识 + 反馈项列表。
 * 重复的 messageId 会使查找产生歧义；重复的 version 会破坏其独立同一性，
 * 因此构造时做唯一性校验。
 *
 * @param createdAt 会话创建时间（Unix 毫秒），用于把侧车行栅栏到一次日志生命周期
 * @param cwd       可选的会话工作目录
 * @param items     当前反馈项（按首次创建顺序）
 */
public record MessageFeedbackRow(
        long createdAt,
        String cwd,
        List<MessageFeedbackItem> items
) {
    public MessageFeedbackRow {
        if (items == null) {
            items = List.of();
        } else {
            validateUnique(items);
            items = List.copyOf(items);
        }
    }

    private static void validateUnique(List<MessageFeedbackItem> items) {
        var messageIds = new java.util.HashSet<String>();
        var versions = new java.util.HashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            MessageFeedbackItem item = items.get(i);
            if (!messageIds.add(item.messageId())) {
                throw new IllegalStateException("duplicate message feedback id '" + item.messageId() + "'");
            }
            if (!versions.add(item.version())) {
                throw new IllegalStateException("duplicate message feedback version '" + item.version() + "'");
            }
        }
    }

    /** 空行（无反馈项）。 */
    public static MessageFeedbackRow empty(long createdAt, String cwd) {
        return new MessageFeedbackRow(createdAt, cwd, List.of());
    }

    /** 返回可变副本，便于在变更中局部修改后重新构造不可变行。 */
    public List<MessageFeedbackItem> mutableItems() {
        return new ArrayList<>(items);
    }
}
