package com.deepseek.dsh.capability.code;

/**
 * 代码运行时能力缝 —— 对应原 Harness 的 {@code ctx.codeRuntime}。
 *
 * <p>提供隔离的代码执行环境，支持多种语言（Python 等）。
 * Code Mode 下，模型通过 {@code run_code} 传输执行代码，结果经工具管线返回。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code CodeRuntimePythonProvider}（Python）/ {@code WorkerThreadProvider}。</li>
 *   <li><b>消费者</b>：Code Mode 的 {@code run_code} 工具。</li>
 * </ul>
 *
 * <p>设计模式：策略 + SPI。
 */
public interface CodeRuntime {

    /**
     * 执行一段代码并返回结果。
     *
     * @param language       语言标识（如 "python"）
     * @param code           源代码
     * @param timeoutSeconds 超时秒数
     */
    CodeResult execute(String language, String code, int timeoutSeconds) throws Exception;
}
