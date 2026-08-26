package com.deepseek.dsh.feedback;

/**
 * 消息反馈项 —— 对应原 Harness 的 {@code MessageFeedbackItem}。
 *
 * <p>一条针对某条助手消息的当前反馈值及其不透明比较并设置（CAS）令牌。
 *
 * @param messageId 目标助手消息的稳定标识
 * @param rating    整体正面/负面判定
 * @param note      可选解释（验证后原样保留；无则为 {@code null}）
 * @param version   不透明等价令牌，每次实质性创建或更新都会被替换
 * @param createdAt 宿主分配的创建时间（Unix 毫秒）
 * @param updatedAt 最近一次实质性更新时间（Unix 毫秒）
 */
public record MessageFeedbackItem(
        String messageId,
        FeedbackRating rating,
        String note,
        String version,
        long createdAt,
        long updatedAt
) {
    public MessageFeedbackItem {
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId must be non-empty");
        }
        if (rating == null) {
            throw new IllegalArgumentException("rating must not be null");
        }
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("version must be non-empty");
        }
    }
}
