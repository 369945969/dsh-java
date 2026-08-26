package com.deepseek.dsh.compaction;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 上下文压缩能力缝 —— 对应原 Harness 的 {@code ctx.compaction}。
 *
 * <p>当对话历史接近上下文窗口上限时，压缩策略裁剪/摘要旧消息，
 * 使 agent 能继续长对话而不超 token 限制。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code BasicCompactionProvider}（摘要）/ {@code ToolResultPruner}（裁剪）。</li>
 *   <li><b>消费者</b>：agent loop 在装配模型请求前调用。</li>
 * </ul>
 *
 * <p>设计模式：策略 + 装饰器（压缩是消息列表上的装饰变换）。
 */
public interface CompactionService extends com.deepseek.dsh.core.context.Service {

    /**
     * 判断给定消息列表是否需要压缩。
     *
     * @param messages       当前消息列表
     * @param tokenEstimate  估算的 token 数
     * @param maxTokens      上下文窗口上限
     */
    boolean needsCompaction(List<ChatMessage> messages, int tokenEstimate, int maxTokens);

    /**
     * 执行压缩，返回裁剪/摘要后的新消息列表。
     *
     * @param messages       当前消息列表
     * @param maxTokens      压缩后目标 token 上限
     */
    List<ChatMessage> compact(List<ChatMessage> messages, int maxTokens);
}
