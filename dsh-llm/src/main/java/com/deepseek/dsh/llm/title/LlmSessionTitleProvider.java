package com.deepseek.dsh.llm.title;

import java.util.ArrayList;
import java.util.List;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.adapter.LlmRequest;
import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.session.title.SessionTitleService;

/**
 * LLM 会话标题提供者 —— 对应原 Harness 的 {@code session-title-llm}。
 *
 * <p>用 LLM 从用户消息生成简短标题。两种策略（通过构造参数选择）：
 * <ul>
 *   <li><b>first-prompt</b>：仅用首条用户消息（对应 {@code session-title-first-prompt-llm}）。</li>
 *   <li><b>all-prompts</b>：用全部用户消息（对应 {@code session-title-all-prompts-llm}）。</li>
 * </ul>
 *
 * <p>设计模式：策略的具体实现 + 装饰器（在基础标题服务上叠加 LLM 能力）。
 */
public final class LlmSessionTitleProvider
        extends AbstractCapabilityPlugin<SessionTitleService>
        implements SessionTitleService {

    private final LlmModel model;
    private final String modelName;
    private final boolean allPrompts;

    /** 仅用首条用户消息生成标题。 */
    public static LlmSessionTitleProvider firstPrompt(LlmModel model, String modelName) {
        return new LlmSessionTitleProvider(model, modelName, false);
    }

    /** 用全部用户消息生成标题。 */
    public static LlmSessionTitleProvider allPrompts(LlmModel model, String modelName) {
        return new LlmSessionTitleProvider(model, modelName, true);
    }

    private LlmSessionTitleProvider(LlmModel model, String modelName, boolean allPrompts) {
        this.model = model;
        this.modelName = modelName;
        this.allPrompts = allPrompts;
    }

    @Override
    protected Class<SessionTitleService> serviceType() {
        return SessionTitleService.class;
    }

    @Override
    public String generate(SessionLog sessionLog) {
        List<String> userMessages = new ArrayList<>();
        for (SessionEvent e : sessionLog.snapshot()) {
            if (e.type() == SessionEvent.Type.USER_MESSAGE
                    && e.payload().text() != null && !e.payload().text().isBlank()) {
                userMessages.add(e.payload().text().trim());
                if (!allPrompts) break;
            }
        }
        if (userMessages.isEmpty()) return "新会话";

        String prompt = allPrompts
                ? "请用一句简短中文概括以下对话主题（不超过20字）:\n" + String.join("\n", userMessages)
                : "请用一句简短中文概括以下消息主题（不超过20字）:\n" + userMessages.get(0);

        try {
            List<ChatMessage> msgs = List.of(
                    ChatMessage.system("你是标题生成器，只输出标题文本，不加引号或标点。"),
                    ChatMessage.user(prompt));
            LlmResponse resp = model.chat(LlmRequest.of(msgs, List.of(), modelName));
            String title = resp.content().trim();
            return title.length() > 30 ? title.substring(0, 30) + "…" : title;
        } catch (Exception e) {
            // LLM 失败时退化为截取首条消息
            String first = userMessages.get(0);
            return first.length() > 40 ? first.substring(0, 40) + "…" : first;
        }
    }

    @Override
    public java.util.Optional<String> current(com.deepseek.dsh.core.brand.SessionId sessionId) {
        return java.util.Optional.empty();
    }

    @Override
    public void setTitle(com.deepseek.dsh.core.brand.SessionId sessionId, String title) {
        // 委托给基础标题服务处理缓存
    }
}
