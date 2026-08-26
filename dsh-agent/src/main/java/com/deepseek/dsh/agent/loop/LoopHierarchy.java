package com.deepseek.dsh.agent.loop;

/**
 * Agent 循环的层级（turn / step / round）。
 *
 * <p>对应原 Harness 的循环层级（{@code docs/glossary.md}）：
 * <ul>
 *   <li><b>turn</b> —— 一次已准入输入的排空，在模型+工具停止或终止策略介入时结束。</li>
 *   <li><b>step</b> —— 一次模型请求加上其响应引起的工具执行；一个 turn 有零或多个 step。</li>
 *   <li><b>round</b> —— 包含一个 turn 的外层策略迭代（如目标轮）。</li>
 * </ul>
 *
 * <p>turn 流程（对应原 Harness 的 turn 生命周期）：
 * <pre>
 *   turn/start
 *     认领下一 step 的输入 + 一条排队消息
 *     装配提示段落 + 工具 schema
 *     -> agent/pre-step (waterfall)   拒绝 | 进入(messages)
 *     step/start
 *       追加进入的消息为 user/message
 *       从日志派生模型历史
 *       agent/request (waterfall) -> llm/stream -> assistant/chunk* -> assistant/message
 *       tool/call* -> tools/pre-execute -> tools/execute -> tools/post-execute -> tool/result*
 *     step/end
 *     工具欠另一次请求，或下一 step 输入到达 -> 认领 -> 下一 step
 *     -> agent/turn-stopping (serial 终止检查点)
 *   turn/end
 * </pre>
 */
public final class LoopHierarchy {

    private LoopHierarchy() {}

    /** turn 的最大 step 数（防止无限循环）。 */
    public static final int DEFAULT_MAX_STEPS = 50;
}
