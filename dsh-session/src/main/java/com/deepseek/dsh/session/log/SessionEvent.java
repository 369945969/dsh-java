package com.deepseek.dsh.session.log;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.deepseek.dsh.core.brand.SessionId;

/**
 * 会话事件 —— 不可变的、仅追加的日志记录，是 agent 状态的真相来源。
 *
 * <p>对应原 Harness 的不变式：<b>"模型可见 ⟺ 已记录"</b>。任何到达模型请求的内容
 * 都必须能从日志重建。{@code SessionEvent} 是追加日志中的原子单元。
 *
 * <p>事件类型包括：用户消息、助手消息、助手分块(chunk)、工具调用、工具结果、
 * turn 开始/结束、step 开始/结束等。
 *
 * <p>设计模式：事件溯源（Event Sourcing）中的领域事件。
 */
public record SessionEvent(
        /** 全局唯一、单调递增的序号（同一会话内）。 */
        long seq,
        /** 所属会话 ID。 */
        SessionId sessionId,
        /** 事件类型。 */
        Type type,
        /** 事件负载（消息、分块、工具调用等）。 */
        Payload payload,
        /** 创建时间。 */
        Instant createdAt,
        /** 可选的父会话/委派深度等谱系信息。 */
        Lineage lineage
) {

    /** 事件类型枚举。 */
    public enum Type {
        TURN_START,
        TURN_END,
        STEP_START,
        STEP_END,
        USER_MESSAGE,
        ASSISTANT_CHUNK,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_RESULT,
        COMMAND
    }

    /** 事件负载 —— 可携带文本、多模态内容或结构化数据。 */
    public record Payload(
            /** 文本内容（消息/分块/结果文本）。 */
            String text,
            /** 结构化内容（工具调用的参数、结果 JSON 等）。 */
            Map<String, Object> structured,
            /** 工具名（仅 TOOL_CALL / TOOL_RESULT 有意义）。 */
            String toolName,
            /** 工具调用 ID（关联 TOOL_CALL 与 TOOL_RESULT）。 */
            String toolCallId
    ) {
        public static Payload text(String text) {
            return new Payload(text, Map.of(), null, null);
        }

        public static Payload toolCall(String toolName, String toolCallId, Map<String, Object> args) {
            return new Payload(null, args, toolName, toolCallId);
        }

        public static Payload toolResult(String toolCallId, String text) {
            return new Payload(text, Map.of(), null, toolCallId);
        }
    }

    /** 谱系信息（子会话/委派）。 */
    public record Lineage(
            SessionId parentSession,
            int delegationDepth
    ) {
        public static Lineage root() {
            return new Lineage(null, 0);
        }
    }

    /** 便捷工厂：从输入列表中派生模型可见消息时使用的投影视图。 */
    public record Projection(List<ChatMessage> messages, long lastSeq) {}
}
