package com.deepseek.dsh.spill;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 保存一次外溢请求 —— 对应原 Harness 的 {@code SaveTextSpill}。
 *
 * <p>把一个工具的超大文本结果持久化到会话作用域的私有文件，
 * 返回面向模型的定位符与检索指引，避免大文本撑爆上下文。
 *
 * @param ownerSessionId 拥有本次外溢的会话头 ID（后端按会话分组存储）
 * @param sourceToolName 产生该结果的工具名（如 {@code web_fetch}）
 * @param sourceCallId   模型下发的调用 ID
 * @param sourceLabel    简短人类标签（如 {@code result} / {@code dispatch}）
 * @param suggestedName   调用方建议的基础名（如 {@code web_fetch.txt}，后端会消毒）
 * @param content         待持久化的完整 UTF-8 文本
 */
public record SaveTextSpill(
        SessionId ownerSessionId,
        String sourceToolName,
        String sourceCallId,
        String sourceLabel,
        String suggestedName,
        String content
) {
    /** 便捷工厂：默认标签为 result。 */
    public static SaveTextSpill of(SessionId owner, String tool, String callId,
                                   String suggestedName, String content) {
        return new SaveTextSpill(owner, tool, callId, "result", suggestedName, content);
    }
}
