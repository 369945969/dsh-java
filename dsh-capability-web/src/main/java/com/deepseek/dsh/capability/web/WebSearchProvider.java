package com.deepseek.dsh.capability.web;

import java.util.List;

/**
 * Web 搜索能力缝 —— 对应原 Harness 的 {@code ctx.web.search}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code DeepSeekSearchProvider} / {@code HttpFetchProvider}。</li>
 *   <li><b>消费者</b>：{@code web_search} / {@code web_fetch} 工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface WebSearchProvider {

    /**
     * 执行一次 web 搜索。
     *
     * @param query    搜索关键词
     * @param maxResults 最多返回条数
     */
    List<SearchResult> search(String query, int maxResults);

    /** 搜索结果。 */
    record SearchResult(
            String title,
            String url,
            String snippet
    ) {
        public static SearchResult of(String title, String url, String snippet) {
            return new SearchResult(title, url, snippet);
        }
    }
}
