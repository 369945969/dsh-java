package com.deepseek.dsh.spill;

import java.util.concurrent.CompletableFuture;

import com.deepseek.dsh.core.context.Service;

/**
 * 外溢存储能力缝 —— 对应原 Harness 的 {@code ctx.spillStore}。
 *
 * <p>抽象「把工具超大文本结果持久化到会话作用域私有产物」这一动作：
 * 只定义「保存什么」，不规定「如何存」。具体后端（本地文件系统、远程、
 * 数据库）子类化并注册为 {@code spillStore} 服务，{@code LocalSpillStore} 是首个实现。
 *
 * <p>实现须遵守的语义：
 * <ul>
 *   <li>{@link #saveText} 原样持久化完整 {@link SaveTextSpill#content()}，
 *       返回不透明定位符、精确字节长度与面向模型的检索指引。</li>
 *   <li>存储按请求会话分组；后端选私有（非全局可读）位置与
 *       由（而非等于）调用方 {@code suggestedName} 派生的无碰撞名。</li>
 *   <li>真实存储失败（权限、磁盘满、后端不可用）须以异常拒绝；
 *       调用方决定降级策略（外溢策略把拒绝视作尽力而为，保留内联结果）。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI（服务提供者接口）。
 */
public interface SpillStore extends Service {

    /**
     * 把 {@code input.content} 持久化到会话作用域外溢产物。
     *
     * @param input 拥有者、调用方来源字段、建议名与完整待存文本
     * @return 已保存产物的 {@link SpillRef}；存储失败时 future 异常完成
     */
    CompletableFuture<SpillRef> saveText(SaveTextSpill input);
}
