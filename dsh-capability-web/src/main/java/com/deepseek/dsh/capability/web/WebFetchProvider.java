package com.deepseek.dsh.capability.web;

/**
 * Web 抓取能力缝 —— 对应原 Harness 的 {@code ctx.web.fetch}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code HttpFetchProvider}（匿名 HTTP GET）。</li>
 *   <li><b>消费者</b>：{@code web_fetch} 工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface WebFetchProvider {

    /**
     * 抓取一个 URL 的内容并转为文本。
     *
     * @param url         要抓取的 URL
     * @param maxChars    最多返回字符数
     * @return 抓取结果（文本）
     */
    String fetch(String url, int maxChars);
}
