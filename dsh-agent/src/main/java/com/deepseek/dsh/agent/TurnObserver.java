package com.deepseek.dsh.agent;

import java.util.List;

import com.deepseek.dsh.session.log.ChatMessage;

/**
 * Turn 观察者 —— 观察 ReAct 循环中的事件，供上层（如 apiproxy 网关）映射为前端事件帧。
 *
 * <p>所有方法默认空实现；观察者按需覆写。
 */
public interface TurnObserver {

    default void onRequestHeader(String systemPrompt, String model) {}

    default void onTurnStart(int turn) {}

    default void onUserMessage(String userMessage, String userMsgId) {}

    default void onAssistantMessage(String content, String reasoning, String assistantMsgId, List<ChatMessage.ToolCall> toolCalls) {}

    default void onAssistantChunk(String contentDelta, String reasoningDelta) {}

    default void onToolCall(String callId, String name, String argumentsJson) {}

    default void onToolResult(String callId, String resultText) {}

    default void onToolDenied(String callId, String reason) {}

    default void onStepStart(int turn, int step) {}

    default void onStepEnd(int turn, int step) {}

    default void onTurnEnd(int turn, String reason) {}
}
