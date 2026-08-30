package com.deepseek.dsh.app.cli;

import java.io.BufferedOutputStream;
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
    private static final String GREEN = TTY ? "\033[32m" : "";
    private static final String RESET = TTY ? "\033[0m" : "";

    private final PrintStream out;
    private boolean streamed = false;
    private boolean inThink = false;
    private long lastFlush = 0;
    private static final long FLUSH_INTERVAL_MS = 50;

    public CliTurnObserver(PrintStream out) {
        this.out = new PrintStream(new BufferedOutputStream(out, 8192), false);
    }

    private void maybeFlush() {
        long now = System.currentTimeMillis();
        if (now - lastFlush >= FLUSH_INTERVAL_MS) {
            out.flush();
            lastFlush = now;
        }
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
            maybeFlush();
            streamed = true;
        }
        if (contentDelta != null && !contentDelta.isEmpty()) {
            if (inThink) {
                out.println();
                out.println("------------------------");
                out.print(RESET);
                inThink = false;
            }
            out.print(contentDelta);
            maybeFlush();
            streamed = true;
        }
    }

    @Override
    public void onAssistantMessage(String content, String reasoning, String assistantMsgId) {
        if (streamed) {
            // 已逐 token 输出：收尾（关闭未闭合 think 块 + 换行），不重复打印正文
            if (inThink) {
                out.println();
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
        String summary = extractSummary(argumentsJson);
        out.print(DIM);
        out.println("> " + name + (summary.isEmpty() ? "" : ": " + summary));
        out.print(RESET);
        out.flush();
    }

    @Override
    public void onToolResult(String callId, String resultText) {
        out.print(GREEN);
        out.println("  ✓");
        out.print(RESET);
        out.flush();
    }

    private static final String[] SUMMARY_FIELDS = {"command", "pattern", "query", "path", "file_path", "url", "task", "directory", "glob"};

    private static String extractSummary(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            for (String field : SUMMARY_FIELDS) {
                var v = node.get(field);
                if (v != null && v.isTextual()) {
                    String s = v.asText();
                    return s.length() > 120 ? s.substring(0, 120) + "…" : s;
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** 折叠空白、去除控制字符（ANSI 转义/二进制等会致乱码）、代理对安全截断到 max 字符，用于单行展示工具参数/结果。 */
    static String collapse(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max + 16));
        int i = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (c < 0x20 || c == 0x7F) continue; // 跳过控制字符（含 ANSI 转义序列），避免终端乱码
            else sb.append(c);
            if (sb.length() > max) break; // 超过 max 即停（保留截断标记）
        }
        String one = sb.toString();
        boolean truncated = i < s.length() || one.length() > max;
        if (!truncated) return one;
        int cut = Math.min(one.length(), max);
        if (cut > 0 && Character.isHighSurrogate(one.charAt(cut - 1))) cut--; // 不在代理对中间截断
        return one.substring(0, cut) + "...";
    }
}
