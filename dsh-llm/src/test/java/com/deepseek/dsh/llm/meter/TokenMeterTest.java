package com.deepseek.dsh.llm.meter;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.llm.adapter.LlmResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 计量器测试 —— 累计/跳过 null usage/多次叠加。
 */
class TokenMeterTest {

    private static LlmResponse resp(int p, int c, int t) {
        return new LlmResponse("content", List.of(), new LlmResponse.TokenUsage(p, c, t), "stop");
    }

    @Test
    void 累计多次响应的token用量() {
        var meter = new TokenMeter();
        meter.record(resp(10, 20, 30));
        meter.record(resp(5, 7, 12));
        assertEquals(15, meter.totalPromptTokens());
        assertEquals(27, meter.totalCompletionTokens());
        assertEquals(42, meter.totalTokens());
    }

    @Test
    void usage为null时跳过不累加() {
        var meter = new TokenMeter();
        meter.record(new LlmResponse("x", List.of(), null, "stop"));
        assertEquals(0, meter.totalPromptTokens());
        assertEquals(0, meter.totalCompletionTokens());
        assertEquals(0, meter.totalTokens());
    }

    @Test
    void 空计量器初始为零() {
        var meter = new TokenMeter();
        assertEquals(0, meter.totalPromptTokens());
        assertEquals(0, meter.totalCompletionTokens());
        assertEquals(0, meter.totalTokens());
    }
}
