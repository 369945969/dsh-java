package com.deepseek.dsh.spill;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.tools.pipeline.ToolExecutionRequest;
import com.deepseek.dsh.tools.pipeline.ToolExecutionResult;
import com.deepseek.dsh.tools.pipeline.ToolMiddleware;

/**
 * 外溢策略中间件 —— 对应原 Harness 的 {@code spill-policy}。
 *
 * <p>工具后执行的结果变换器：当一个纯文本结果的 UTF-8 字节超过
 * {@code maxInlineBytes} 时，把完整文本保存到会话作用域外溢产物
 * （{@link SpillStore}），并把面向模型的结果替换为有界的头/尾预览 +
 * 定位符与检索指引。一个 {@code read} 工具被跳过，避免
 * 「读 → 外溢 → 再读」死循环。
 *
 * <p><b>尽力而为</b>：无会话拥有者、无 {@code spillStore} 后端、或保存失败
 * ⇒ 记日志并保留原内联结果。外溢失败绝不能把一次成功工具调用变成错误，
 * 也不能隐藏内联内容。
 *
 * <p>设计模式：责任链中间件（装饰 next 后再加工结果）。
 */
public final class SpillPolicy implements ToolMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SpillPolicy.class);
    private static final String READ_TOOL = "read";

    private final long maxInlineBytes;
    private final SpillStore spillStore;

    /**
     * @param maxInlineBytes 面向模型的上下文上限（字节）；<=0 表示禁用（no-op）
     * @param spillStore     外溢后端；为 null 时本策略 no-op
     */
    public SpillPolicy(long maxInlineBytes, SpillStore spillStore) {
        this.maxInlineBytes = maxInlineBytes;
        this.spillStore = spillStore;
    }

    @Override
    public ToolExecutionResult handle(ToolExecutionRequest request,
                                      Next<ToolExecutionRequest, ToolExecutionResult> next) {
        // 先委托下游（如钩子）敲定结果，再对其加工
        ToolExecutionResult result = next.proceed(request);
        if (maxInlineBytes <= 0 || spillStore == null) return result;
        if (result.isError()) return result;
        // 跳过 read，避免读 → 外溢 → 再读循环
        if (READ_TOOL.equals(request.toolName())) return result;

        String text = result.text();
        if (text == null) return result;
        long totalBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (totalBytes <= maxInlineBytes) return result;

        String replaced = spillReplacement(text, totalBytes, request);
        if (replaced == null) return result;
        return new ToolExecutionResult(result.toolCallId(), replaced, false);
    }

    /** 外溢并构造有界替换（预览 + 通知），无法替换时返回 null 以保留原内容。 */
    private String spillReplacement(String text, long totalBytes, ToolExecutionRequest request) {
        SessionId sessionId = request.context().sessionId();
        String toolName = request.toolName();
        String callId = request.toolCallId();

        SaveTextSpill save = new SaveTextSpill(
                sessionId, toolName, callId, "result", toolName + ".txt", text);
        SpillRef ref;
        try {
            ref = spillStore.saveText(save).join();
        } catch (Exception e) {
            log.warn("spill-policy: {} 保存外溢失败，保留内联内容: {}", toolName, e.toString());
            return null;
        }

        // 为通知预留字节成本，使替换（预览+通知）不超过上限。
        // 预留用「最坏情况」通知：省略量按总字节数计（位数最多），
        // 故真实通知（省略更少、位数不更多）必不超过预留，最终替换保证 <= 上限。
        String worstNotice = "(" + describeOmitted(totalBytes, 0)
                + " 完整结果已存于: " + ref.locator() + ". " + ref.retrievalHint() + ")";
        long reserve = worstNotice.getBytes(StandardCharsets.UTF_8).length + 2; // +2 为 \n\n 连接
        long budget = Math.max(0, maxInlineBytes - reserve);
        String preview = preview(text, budget);

        // 用真实预览字节数描述省略量，使数字准确（位数 <= 最坏情况）
        long previewBytes = preview.isEmpty() ? 0
                : preview.getBytes(StandardCharsets.UTF_8).length;
        String notice = "(" + describeOmitted(totalBytes, previewBytes)
                + " 完整结果已存于: " + ref.locator() + ". " + ref.retrievalHint() + ")";
        String replaced = preview.isEmpty() ? notice : preview + "\n\n" + notice;
        // 安全网：通知自身超上限时无有界替换，保留内联（外溢文件是无害孤儿）
        if (replaced.getBytes(StandardCharsets.UTF_8).length > maxInlineBytes) {
            log.warn("spill-policy: {} 的外溢通知超过上限，保留内联内容", toolName);
            return null;
        }
        return replaced;
    }

    private static final String PREVIEW_SEPARATOR = "\n…（省略）…\n";

    /** 头/尾预览：预算平分给两端，含分隔符在内不超过预算。 */
    private static String preview(String text, long budget) {
        if (budget <= 0) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= budget) return text;
        int sepLen = PREVIEW_SEPARATOR.getBytes(StandardCharsets.UTF_8).length;
        int avail = (int) Math.max(0, budget - sepLen);
        if (avail <= 0) return "";
        int head = (int) Math.ceil(avail / 2.0);
        int tail = avail - head;
        String headText = new String(bytes, 0, head, StandardCharsets.UTF_8);
        String tailText = new String(bytes, bytes.length - tail, tail, StandardCharsets.UTF_8);
        return headText + PREVIEW_SEPARATOR + tailText;
    }

    /** 省略描述（字节）：被外溢的完整字节，减去预览保留的部分。 */
    private static String describeOmitted(long totalBytes, long previewBytes) {
        long omitted = Math.max(0, totalBytes - previewBytes);
        return "省略约 " + omitted + " 字节";
    }

    /** 便捷工厂：从上下文获取 spillStore，缺失则 no-op。 */
    public static SpillPolicy fromContext(long maxInlineBytes,
                                          com.deepseek.dsh.core.context.Context ctx) {
        Optional<SpillStore> store = ctx.get(SpillStore.class);
        return new SpillPolicy(maxInlineBytes, store.orElse(null));
    }
}
