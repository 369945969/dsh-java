package com.deepseek.dsh.llm.meter;

import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.llm.adapter.LlmResponse;

/**
 * Token 用量计量服务能力缝 —— 对应原 Harness 的 token 计量能力。
 */
public interface TokenMeterService extends Service {

    /** 记录一次模型调用的 token 用量。 */
    void record(LlmResponse response);

    /** 累计 prompt token。 */
    long totalPromptTokens();

    /** 累计 completion token。 */
    long totalCompletionTokens();

    /** 累计总 token。 */
    long totalTokens();
}
