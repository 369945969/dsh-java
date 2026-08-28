package com.deepseek.dsh.app.cli;

import java.io.PrintStream;

import com.deepseek.dsh.agent.TurnObserver;

/**
 * CLI 回合观察者 —— 把 ReAct 每 step 的事件渲染为终端文本，与 web chat 的
 * {@code TurnObserver} 帧映射同构（这里直接打到 {@link PrintStream}）：
 * <ul>
 *   <li>{@code onAssistantChunk}：模型逐 token 生成时即推送（正文 + reasoning），
 *       think 走暗色块、正文原样流式——边想边出（如 Hermes）；</li>
 *   <li>{@code onAssistantMessage}：若已流式输出则只收尾（不重复打印）；否则回退到整段输出
 *       （模型不支持流式时）think 块 + 正文；</li>
 *   <li>{@code onToolCall}/{@code onToolResult}：工具名 + 折叠参数 / 截断结果。</li>
 * </ul>
 * 仅 TTY 输出 ANSI dim，管道为纯文本。reasoning 来自模型 reasoning_content（如 glm-5.2）。
 *
 * <p>设计模式：观察者（渲染适配器）—— 从 {@link TurnObserver} 事件到终端格式。
 */
public final class CliTurnObserver implements TurnObserver {

    private static final boolean TTY = System.console() != null;
    private static final String DIM = TTY ? "\033[2m" : "";
    private static final String RESET = TTY ? "\033[0m" : "";

    private final PrintStream out;
    private boolean streamed = false;   // 本 step 是否已逐 token 输出
    private boolean inThink = false;   // think 暗色块是否处于打开状态

    public CliTurnObserver(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onAssistantChunk(String contentDelta, String reasoningDelta) {
        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
            if (!inThink) {
                out.print(DIM);
                out.println("-- think ----------------");
                inThink = true;
            }
            out.print(reasoningDelta);
            out.flush();
            streamed = true;
        }
        if (contentDelta != null && !contentDelta.isEmpty()) {
            if (inThink) {
                out.println("------------------------");
                out.print(RESET);
                inThink = false;
            }
            out.print(contentDelta);
            out.flush();
            streamed = true;
        }
    }

    @Override
    public void onAssistantMessage(String content, String reasoning) {
        if (streamed) {
            // 已逐 token 输出：收尾（关闭未闭合 think 块 + 换行），不重复打印正文
            if (inThink) {
                out.println("------------------------");
                out.print(RESET);
                inThink = false;
            }
            out.println();
            out.flush();
            streamed = false;
            return;
        }
        // 回退（模型不支持流式 / 未推送增量）：整段输出 think 块 + 正文
        if ((content == null || content.isEmpty()) && (reasoning == null || reasoning.isEmpty())) return;
        if (reasoning != null && !reasoning.isEmpty()) {
            out.print(DIM);
            out.println("-- think ----------------");
            for (String line : reasoning.split("\n", -1)) out.println("  " + line);
            out.println("------------------------");
            out.print(RESET);
            out.flush();
        }
        if (content != null && !content.isEmpty()) {
            out.println(content);
            out.flush();
        }
    }

    @Override
    public void onToolCall(String callId, String name, String argumentsJson) {
        out.print(DIM);
        out.println("> " + name + "  " + collapse(argumentsJson, 200));
        out.print(RESET);
        out.flush();
    }

    @Override
    public void onToolResult(String callId, String resultText) {
        out.print(DIM);
        out.println("  -> " + collapse(resultText, 600));
        out.print(RESET);
        out.flush();
    }

    /** 折叠换行并截断到 max 字符，用于单行展示工具参数/结果。 */
    static String collapse(String s, int max) {
        if (s == null) return "";
        String one = s.replace("\r", " ").replace("\n", " ");
        return one.length() > max ? one.substring(0, max) + "..." : one;
    }
}
