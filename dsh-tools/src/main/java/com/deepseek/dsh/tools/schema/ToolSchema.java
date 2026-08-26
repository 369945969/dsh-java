package com.deepseek.dsh.tools.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具的 JSON Schema 描述 —— 用于在模型请求中自动装配工具定义。
 *
 * <p>对齐 OpenAI/DeepSeek 的 function-calling 工具 schema 格式。
 * 推荐通过 {@link Builder} 构造，避免手写 {@code Map.of} 样板。
 */
public record ToolSchema(
        /** 工具名（模型可见）。 */
        String name,
        /** 人类可读描述。 */
        String description,
        /** 参数 JSON Schema（{@code properties}、{@code required} 等）。 */
        Map<String, Object> parameters
) {
    public static ToolSchema of(String name, String description, Map<String, Object> parameters) {
        return new ToolSchema(name, description, parameters);
    }

    /**
     * 流式构建器 —— 消除每个工具重复的 {@code Map.of("type","object","properties",...)} 样板。
     *
     * <pre>{@code
     * ToolSchema.builder("read", "读取文件内容")
     *     .string("path", "文件绝对路径", true)
     *     .intProp("offset", "起始行号（1-based）")
     *     .intProp("limit", "读取行数")
     *     .build();
     * }</pre>
     */
    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /** Schema 流式构建器。 */
    public static final class Builder {
        private final String name;
        private final String description;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /** 添加一个 string 参数。 */
        public Builder string(String field, String desc) {
            return string(field, desc, false);
        }

        public Builder string(String field, String desc, boolean isRequired) {
            properties.put(field, Map.of("type", "string", "description", desc));
            if (isRequired) required.add(field);
            return this;
        }

        /** 添加一个 integer 参数。 */
        public Builder intProp(String field, String desc) {
            properties.put(field, Map.of("type", "integer", "description", desc));
            return this;
        }

        public Builder intProp(String field, String desc, boolean isRequired) {
            properties.put(field, Map.of("type", "integer", "description", desc));
            if (isRequired) required.add(field);
            return this;
        }

        /** 添加一个 boolean 参数。 */
        public Builder bool(String field, String desc) {
            properties.put(field, Map.of("type", "boolean", "description", desc));
            return this;
        }

        /** 添加一个带 enum 约束的 string 参数。 */
        public Builder enumStr(String field, List<String> values, String desc, boolean isRequired) {
            properties.put(field, Map.of(
                    "type", "string", "enum", values, "description", desc));
            if (isRequired) required.add(field);
            return this;
        }

        /** 添加任意自定义参数 schema。 */
        public Builder raw(String field, Map<String, Object> schema) {
            properties.put(field, schema);
            return this;
        }

        /** 构建不可变 ToolSchema。 */
        public ToolSchema build() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("properties", Map.copyOf(properties));
            params.put("required", List.copyOf(required));
            return new ToolSchema(name, description, Map.copyOf(params));
        }
    }
}
