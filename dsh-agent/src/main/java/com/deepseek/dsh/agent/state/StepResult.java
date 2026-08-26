package com.deepseek.dsh.agent.state;

import java.util.List;

import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.session.log.ChatMessage;

/**
 * 一次 step 的产物 —— 模型响应 + 待执行的工具调用。
 */
public record StepResult(
        /** 模型响应。 */
        LlmResponse response,
        /** 投影后的模型消息列表（含本次 step 的助手消息）。 */
        List<ChatMessage> messages
) {}
