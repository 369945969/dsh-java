package com.deepseek.dsh.tools.registry;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.deepseek.dsh.core.context.Service;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 工具服务能力缝 —— 对应原 Harness 的 {@code ctx.tools}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@link ToolRegistry}。</li>
 *   <li><b>消费者</b>：agent loop（装配提示）与 MCP 客户端（注册外部工具）。</li>
 * </ul>
 */
public interface Tools extends Service {

    /** 按名获取工具。 */
    Optional<Tool> get(String name);

    /** 全部工具的 schema 列表（用于装配模型提示）。 */
    List<ToolSchema> schemas();

    /** 全部工具名集合。 */
    Set<String> names();
}
