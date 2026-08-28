package com.deepseek.dsh.agent;

/**
 * Turn 观察者 —— 观察 ReAct 循环中的事件，供上层（如 apiproxy 网关）映射为前端事件帧。
 *
 * <p>所有方法默认空实现；观察者按需覆写。在 {@link ReActAgentLoop} 的 turn 生命周期内触发：
 * <ul>
 *   <li>{@link #onAssistantMessage} —— 每一步模型回复后（含中间步与最终步）。</li>
 *   <li>{@link #onToolCall} —— 工具调用前（已通过权限/审批）。</li>
 *   <li>{@link #onToolResult} —— 工具执行返回后。</li>
 * </ul>
 *
 * <p>设计模式：观察者（解耦循环骨架与事件投射）。
 */
public interface TurnObserver {

    /** 某一步模型回复：最终回复内容 + 推理内容（reasoning 模型的 reasoning_content，普通模型为空）。 */
    default void onAssistantMessage(String content, String reasoning) {}

    /**
     * 流式增量：模型逐 token 生成时推送（正文 delta 与推理 reasoningDelta，可同时为 null/空）。
     * 在 {@link #onAssistantMessage} 之前触发；若模型不支持流式（回退到 chat），则不触发，
     * 由 {@link #onAssistantMessage} 负责整段输出。CLI 据此边想边出（如 Hermes）。
     */
    default void onAssistantChunk(String contentDelta, String reasoningDelta) {}

    /** 一次工具调用（已过权限）：callId、工具名、参数 JSON。 */
    default void onToolCall(String callId, String name, String argumentsJson) {}

    /** 一次工具结果：callId、结果文本。 */
    default void onToolResult(String callId, String resultText) {}
}
