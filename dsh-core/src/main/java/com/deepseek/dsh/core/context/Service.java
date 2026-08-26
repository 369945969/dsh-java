package com.deepseek.dsh.core.context;

/**
 * 能力服务的标记接口 —— 实现 {@link Plugin} 的服务可同时实现此接口，
 * 以表明自身是一个可被 {@code ctx.get} 查询的领域服务。
 *
 * <p>对应原 Harness 的 "能力缝（capability seam）"：一个完整的可替换能力，
 * 由三部分组成：
 * <ul>
 *   <li><b>服务定义</b> —— 拥有 {@code ctx.<key>} 与词汇类型的抽象（本接口或其子接口）。</li>
 *   <li><b>服务提供者</b> —— 具体实现。</li>
 *   <li><b>消费者</b> —— 通常为面向模型的工具，注入该服务。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI（服务提供者接口）。
 */
public interface Service {
}
