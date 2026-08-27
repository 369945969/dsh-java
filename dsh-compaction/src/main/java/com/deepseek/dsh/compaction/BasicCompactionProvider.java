package com.deepseek.dsh.compaction;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 基础压缩提供者 —— 对应原 Harness 的 {@code compaction-basic}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractCapabilityPlugin}，消除样板。
 *
 * <p>策略：保留系统提示 + 最近 N 条消息，将更早的消息用 LLM 摘要为一条 system 消息。
 *
 * <p>设计模式：策略的具体实现 + 模板方法（插件基类）。
 */
public final class BasicCompactionProvider
        extends AbstractCapabilityPlugin<CompactionService>
        implements CompactionService {

    private static final Logger log = LoggerFactory.getLogger(BasicCompactionProvider.class);

    /** 保留最近消息的条数（不参与摘要）。 */
    private final int keepRecent;
    /** 触发压缩的 token 比例阈值（占窗口的百分比）。 */
    private final double thresholdRatio;

    public BasicCompactionProvider() {
        this(8, 0.8);
    }

    public BasicCompactionProvider(int keepRecent, double thresholdRatio) {
        this.keepRecent = keepRecent;
        this.thresholdRatio = thresholdRatio;
    }

    @Override
    protected Class<CompactionService> serviceType() {
        return CompactionService.class;
    }

    @Override
    public boolean needsCompaction(List<ChatMessage> messages, int tokenEstimate, int maxTokens) {
        return tokenEstimate > maxTokens * thresholdRatio;
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> messages, int maxTokens) {
        if (messages.size() <= keepRecent) return messages;

        List<ChatMessage> toSummarize = new ArrayList<>(messages.subList(0, messages.size() - keepRecent));
        List<ChatMessage> toKeep = new ArrayList<>(messages.subList(messages.size() - keepRecent, messages.size()));

        ChatMessage systemMsg = null;
        if (!toSummarize.isEmpty() && toSummarize.get(0).role() == ChatMessage.Role.SYSTEM) {
            systemMsg = toSummarize.remove(0);
        }

        String summary = summarize(toSummarize);

        List<ChatMessage> result = new ArrayList<>();
        if (systemMsg != null) result.add(systemMsg);
        result.add(ChatMessage.system("[Conversation summary] " + summary));
        result.addAll(toKeep);
        log.debug("Compacted: {} entries → {} entries", messages.size(), result.size());
        return result;
    }

    /**
     * 将旧消息摘要为一段文本。无 LLM 时退化为截取。
     */
    private String summarize(List<ChatMessage> messages) {
        StringBuilder conv = new StringBuilder();
        for (ChatMessage m : messages) {
            conv.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        // 无注入 LLM 时退化为截取前 500 字
        String text = conv.toString();
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
}
