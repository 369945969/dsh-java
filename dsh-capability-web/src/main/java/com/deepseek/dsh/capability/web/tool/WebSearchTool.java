package com.deepseek.dsh.capability.web.tool;

import java.util.stream.Collectors;

import com.deepseek.dsh.capability.web.WebSearchProvider;
import com.deepseek.dsh.tools.registry.AbstractTool;
import com.deepseek.dsh.tools.registry.ToolArgs;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * web_search 工具 —— 对应原 Harness 的 {@code tool-web} web_search。
 *
 * <p>设计模式：命令（Command）+ 模板方法。
 */
public final class WebSearchTool extends AbstractTool {

    private final WebSearchProvider searchProvider;

    public WebSearchTool(WebSearchProvider searchProvider) {
        this.searchProvider = searchProvider;
    }

    @Override
    protected ToolSchema buildSchema() {
        return ToolSchema.builder("web_search", "搜索网络获取信息。")
                .string("query", "搜索关键词", true)
                .intProp("max_results", "最多返回条数（默认 5）")
                .build();
    }

    @Override
    protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
        String query = args.requiredString("query");
        int max = args.optionalInt("max_results", 5);
        var results = searchProvider.search(query, max);
        if (results.isEmpty()) return "（无搜索结果）";
        return results.stream()
                .map(r -> "【" + r.title() + "】\n" + r.url() + "\n" + r.snippet())
                .collect(Collectors.joining("\n\n"));
    }
}
