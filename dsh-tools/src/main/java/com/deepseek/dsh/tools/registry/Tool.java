package com.deepseek.dsh.tools.registry;

import java.util.Map;

import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 工具 —— 面向模型的能力单元。每个工具是模型可调用的一条命令。
 *
 * <p>实现需提供：
 * <ul>
 *   <li>{@link #schema()} —— 工具的 JSON Schema，在装配提示时自动加入。</li>
 *   <li>{@link #invoke(Map, ToolContext)} —— 执行工具并返回文本结果。</li>
 * </ul>
 *
 * <p>设计模式：命令（Command）—— 每个工具是一个可执行的命令对象。
 */
public interface Tool {

    /** 工具的 JSON Schema（名字、描述、参数）。 */
    ToolSchema schema();

    /**
     * 执行工具。
     *
     * @param arguments 模型提供的参数（已解析为 Map）
     * @param ctx       工具执行上下文（提供 session、审批等依赖）
     * @return 返回给模型的文本结果
     */
    String invoke(Map<String, Object> arguments, ToolContext ctx) throws Exception;
}
