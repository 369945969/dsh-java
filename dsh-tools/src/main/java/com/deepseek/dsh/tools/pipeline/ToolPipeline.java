package com.deepseek.dsh.tools.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.exception.ToolException;
import com.deepseek.dsh.core.middleware.MiddlewareChain;
import com.deepseek.dsh.tools.registry.Tool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.registry.Tools;

/**
 * 工具执行管线 —— 用中间件链包装工具的实际调用。
 *
 * <p>对应原 Harness 的工具执行管线：{@code tools/pre-execute} →
 * {@code tools/execute} → {@code tools/post-execute}。中间件按序包裹，
 * 最内层是实际工具调用。
 *
 * <p><b>重构要点</b>：
 * <ul>
 *   <li>中间件现在<b>真正接入</b>链中（此前被忽略）—— 通过 Builder 依次 add。</li>
 *   <li>ToolContext 随请求贯穿链，<b>移除 ThreadLocal</b>（更安全、虚拟线程友好）。</li>
 *   <li>工具未找到 / 执行失败抛 {@link ToolException}，由管线转为 error 结果，
 *       不再 catch 丢失类型。</li>
 * </ul>
 *
 * <p>设计模式：责任链调度器 + 命令调用者（Invoker）。
 */
public final class ToolPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolPipeline.class);

    private final Tools tools;
    private final MiddlewareChain<ToolExecutionRequest, ToolExecutionResult> chain;

    public ToolPipeline(Tools tools) {
        this(tools, List.of());
    }

    public ToolPipeline(Tools tools, List<ToolMiddleware> middlewares) {
        this.tools = tools;
        // 真正将中间件逐个加入链：最先添加的最外层，终端是 invokeTool
        MiddlewareChain.Builder<ToolExecutionRequest, ToolExecutionResult> builder =
                MiddlewareChain.builder(this::invokeTool);
        for (ToolMiddleware mw : middlewares) {
            builder.add(mw);
        }
        this.chain = builder.build();
    }

    /**
     * 执行一次工具调用，经过完整中间件链。
     *
     * <p>边界异常处理：中间件/终端抛出的 {@link ToolException} 在此转化为
     * error 结果（记录到会话日志），领域异常上下文不丢失（记入日志）。
     * 非预期 {@link RuntimeException} 原样传播（可能需要中止 turn）。
     */
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        try {
            return chain.dispatch(request);
        } catch (ToolException e) {
            log.warn("[tool] {} invocation failed (recoverable={}): {}",
                    e.toolName(), e.isRecoverable(), e.getMessage());
            return ToolExecutionResult.error(e.toolCallId(), e.getMessage());
        }
    }

    /** 便捷重载：自动构建带上下文的请求。 */
    public ToolExecutionResult execute(String toolName, String toolCallId,
                                       java.util.Map<String, Object> arguments, ToolContext ctx) {
        return execute(new ToolExecutionRequest(toolName, toolCallId, arguments, ctx));
    }

    /** 终端处理器：查找并调用工具。 */
    private ToolExecutionResult invokeTool(ToolExecutionRequest request) {
        var toolOpt = tools.get(request.toolName());
        if (toolOpt.isEmpty()) {
            throw new ToolException(request.toolName(), request.toolCallId(),
                    "Unknown tool: " + request.toolName(), null, false);
        }
        Tool tool = toolOpt.get();
        try {
            String result = tool.invoke(request.arguments(), request.context());
            return ToolExecutionResult.ok(request.toolCallId(), result == null ? "" : result);
        } catch (ToolException e) {
            throw e; // 工具自身已抛领域异常，原样传播
        } catch (Exception e) {
            log.warn("Tool {} execution failed: {}", request.toolName(), e.toString());
            throw new ToolException(request.toolName(), request.toolCallId(),
                    "工具执行失败: " + e.getMessage(), e, false);
        }
    }

    /** 管线构建器 —— 流式添加中间件。 */
    public static final class Builder {
        private final Tools tools;
        private final List<ToolMiddleware> middlewares = new ArrayList<>();

        public Builder(Tools tools) {
            this.tools = tools;
        }

        public Builder middleware(ToolMiddleware mw) {
            middlewares.add(mw);
            return this;
        }

        public ToolPipeline build() {
            return new ToolPipeline(tools, List.copyOf(middlewares));
        }
    }
}
