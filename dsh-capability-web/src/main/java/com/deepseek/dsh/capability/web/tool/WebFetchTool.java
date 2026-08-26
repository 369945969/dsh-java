package com.deepseek.dsh.capability.web.tool;

import com.deepseek.dsh.capability.web.WebFetchProvider;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * web_fetch 工具 —— 对应原 Harness 的 {@code tool-web} web_fetch。
 *
 * <p>抓取一个 URL 的内容并转为文本返回。
 */
public final class WebFetchTool extends AbstractTool {

    private final WebFetchProvider fetchProvider;

    public WebFetchTool(WebFetchProvider fetchProvider) {
        this.fetchProvider = fetchProvider;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("web_fetch", "抓取 URL 内容并返回文本。")
                .string("url", "要抓取的 URL", true)
                .intProp("max_chars", "最多返回字符数（默认 10000）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String url = args.requiredString("url");
        int maxChars = args.optionalInt("max_chars", 10000);
        return fetchProvider.fetch(url, maxChars);
    }
}
