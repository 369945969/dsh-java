package com.deepseek.dsh.core.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统提示注入事件 —— 各上下文插件向系统提示追加命名段落。
 *
 * <p>对应原 Harness 的系统提示组装：agent 在装配每轮模型请求前分发此事件，
 * 监听插件（agent-instructions / time / skill-catalog 等）通过 {@link #appendSection}
 * 贡献段落，{@link #compose()} 合并为系统提示的附加部分。分发用事件总线的
 * waterfall 模式：监听器就地修改事件并调用 {@code next} 继续。
 *
 * <p>设计模式：观察者（waterfall 中间件，就地修改事件）。
 */
public final class SystemPromptInjectEvent {

    private final Map<String, String> sections = new LinkedHashMap<>();

    /** 追加/覆盖一个命名段落（顺序按首次追加）。 */
    public void appendSection(String name, String content) {
        sections.put(name, content);
    }

    /** 合并全部段落为系统提示附加文本（段落间空行分隔）。 */
    public String compose() {
        return String.join("\n\n", sections.values());
    }

    /** 是否无任何段落。 */
    public boolean isEmpty() {
        return sections.isEmpty();
    }
}
