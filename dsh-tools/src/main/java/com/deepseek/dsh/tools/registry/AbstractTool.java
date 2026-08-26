package com.deepseek.dsh.tools.registry;

import java.util.Map;

import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 工具抽象基类 —— 缓存 schema 并提供 ToolArgs 工厂，消除每个工具的样板。
 *
 * <p>子类只需实现 {@link #buildSchema()}（用 {@link ToolSchema.Builder}）和
 * {@link #execute(ToolArgs, ToolContext)}，无需手写 schema 缓存与参数提取。
 *
 * <p>设计模式：模板方法（Template Method）—— {@link #schema()} 与
 * {@link #invoke(Map, ToolContext)} 定义骨架，子类填入具体步骤。
 */
public abstract class AbstractTool implements Tool {

    private volatile ToolSchema cachedSchema;

    @Override
    public final ToolSchema schema() {
        if (cachedSchema == null) {
            cachedSchema = buildSchema();
        }
        return cachedSchema;
    }

    @Override
    public final String invoke(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        ToolArgs args = new ToolArgs(schema().name(), arguments);
        return execute(args, ctx);
    }

    /** 子类实现：用 {@link ToolSchema.Builder} 构建 schema（只调用一次）。 */
    protected abstract ToolSchema buildSchema();

    /** 子类实现：用 {@link ToolArgs} 类型安全地提取参数并执行。 */
    protected abstract String execute(ToolArgs args, ToolContext ctx) throws Exception;
}
