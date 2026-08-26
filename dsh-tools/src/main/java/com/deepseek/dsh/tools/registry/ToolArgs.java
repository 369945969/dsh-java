package com.deepseek.dsh.tools.registry;

import java.util.Map;

import com.deepseek.dsh.core.exception.ToolException;

/**
 * 类型安全的工具参数提取器 —— 消除每个工具重复的
 * {@code arguments.containsKey("x") ? ((Number) arguments.get("x")).intValue() : def} 样板。
 *
 * <p>缺失或类型不符时抛出 {@link ToolException}（而非返回 null 或抛 ClassCastException），
 * 异常携带工具名与参数名，便于诊断。
 *
 * <p>设计模式：解释器（Interpreter）—— 将松散的 Map 参数解释为类型安全值。
 */
public final class ToolArgs {

    private final String toolName;
    private final Map<String, Object> args;

    public ToolArgs(String toolName, Map<String, Object> args) {
        this.toolName = toolName;
        this.args = args != null ? args : Map.of();
    }

    /** 提取必填 string 参数。 */
    public String requiredString(String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new ToolException(toolName, "缺少必填参数: " + key);
        }
        return v.toString();
    }

    /** 提取可选 string 参数，缺失返回默认值。 */
    public String optionalString(String key, String defaultValue) {
        Object v = args.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    /** 提取必填 int 参数。 */
    public int requiredInt(String key) {
        Object v = args.get(key);
        if (v == null) {
            throw new ToolException(toolName, "缺少必填参数: " + key);
        }
        if (v instanceof Number n) return n.intValue();
        throw new ToolException(toolName, "参数 " + key + " 应为整数，实际: " + v.getClass().getSimpleName());
    }

    /** 提取可选 int 参数，缺失返回默认值。 */
    public int optionalInt(String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        throw new ToolException(toolName, "参数 " + key + " 应为整数，实际: " + v.getClass().getSimpleName());
    }

    /** 参数是否存在。 */
    public boolean has(String key) {
        return args.containsKey(key);
    }
}
