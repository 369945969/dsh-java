package com.deepseek.dsh.schedule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepseek.dsh.session.log.SessionEvent;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.tools.registry.Tool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.deepseek.dsh.tools.schema.ToolSchema;

/**
 * 调度管理工具 —— 对应原 Harness 的 {@code schedule_create}/{@code schedule_list}/
 * {@code schedule_delete}。
 *
 * <p>三个工具在持久会话折叠上操作：创建（验证选择器、分配 ID、追加变更）、
 * 列出（折叠后派生视图）、删除（折叠后按 ID 删除）。
 * 成功与失败均以规范 JSON 文本返回（对齐 TS 的 {@code renderValue}）。
 *
 * <p>设计模式：命令（Command）—— 每个工具是一个可执行的命令对象。
 */
public final class ScheduleTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduleTools() {
    }

    /** 创建一个绑定到指定会话供应者的 schedule_create 工具。 */
    public static Tool createTool(Function<Void, SessionLog> sessionSupplier) {
        return new ScheduleCreateTool(sessionSupplier);
    }

    /** 创建一个绑定到指定会话供应者的 schedule_list 工具。 */
    public static Tool listTool(Function<Void, SessionLog> sessionSupplier) {
        return new ScheduleListTool(sessionSupplier);
    }

    /** 创建一个绑定到指定会话供应者的 schedule_delete 工具。 */
    public static Tool deleteTool(Function<Void, SessionLog> sessionSupplier) {
        return new ScheduleDeleteTool(sessionSupplier);
    }

    /** 一次性注册三个调度工具。 */
    public static List<Tool> all(Function<Void, SessionLog> sessionSupplier) {
        return List.of(createTool(sessionSupplier), listTool(sessionSupplier), deleteTool(sessionSupplier));
    }

    // ---- JSON 序列化 --------------------------------------------------

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"code\":\"internal_error\",\"message\":\"The schedule operation failed.\"}";
        }
    }

    static Map<String, Object> error(ScheduleException.Code code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code.wire());
        m.put("message", message);
        return m;
    }

    static Map<String, Object> view(ScheduleRecord record, long now) {
        ScheduleDomain.ScheduleView v = ScheduleDomain.scheduleView(record, now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", record.id().value());
        m.put("kind", record.kind());
        m.put("prompt", record.prompt());
        m.put("scheduledAt", record.scheduledAt());
        m.put("state", v.state());
        m.put("deliveryMode", v.deliveryMode());
        switch (record) {
            case ScheduleRecord.After a -> m.put("afterSeconds", a.afterSeconds());
            case ScheduleRecord.Every e -> m.put("everySeconds", e.everySeconds());
            case ScheduleRecord.At ignored -> { }
        }
        return m;
    }

    // ---- 工具实现 -----------------------------------------------------

    private abstract static class ScheduleToolBase implements Tool {
        protected final Function<Void, SessionLog> sessionSupplier;

        ScheduleToolBase(Function<Void, SessionLog> sessionSupplier) {
            this.sessionSupplier = sessionSupplier;
        }

        protected SessionLog session() {
            return sessionSupplier.apply(null);
        }

        protected FoldedSchedules foldOrError() {
            SessionLog session = session();
            if (session == null) {
                throw new ScheduleException(ScheduleException.Code.INTERNAL_ERROR,
                        "No active session for schedule operation.");
            }
            return ScheduleDomain.foldChanges(ScheduleChangeCodec.extract(session.snapshot()));
        }
    }

    static final class ScheduleCreateTool extends ScheduleToolBase {
        ScheduleCreateTool(Function<Void, SessionLog> sessionSupplier) {
            super(sessionSupplier);
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.of("schedule_create",
                    "Create one reminder in the current session. Supply a non-empty prompt and exactly "
                            + "one selector: a positive safe-integer after_seconds delay, at as a strict "
                            + "offset date-time or local date/time object, or safe-integer every_seconds "
                            + "of at least " + ScheduleDomain.MIN_EVERY_INTERVAL_SECONDS + ".",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "prompt", Map.of("type", "string",
                                            "description", "Reminder content to present when the target becomes due."),
                                    "after_seconds", Map.of("type", "number",
                                            "description", "Positive safe-integer delay in seconds."),
                                    "every_seconds", Map.of("type", "number",
                                            "description", "Fixed-rate safe-integer interval in seconds, at least "
                                                    + ScheduleDomain.MIN_EVERY_INTERVAL_SECONDS + "."),
                                    "at", Map.of("description",
                                            "Absolute target as strict offset RFC 3339 or local date/time with an explicit IANA zone.")),
                            "required", List.of("prompt")));
        }

        @Override
        public String invoke(Map<String, Object> arguments, ToolContext ctx) {
            SessionLog session = session();
            if (session == null) {
                return json(error(ScheduleException.Code.INTERNAL_ERROR, "No active session."));
            }
            String prompt = arguments.get("prompt") == null ? null : arguments.get("prompt").toString();
            boolean hasAfter = arguments.containsKey("after_seconds");
            boolean hasAt = arguments.containsKey("at");
            boolean hasEvery = arguments.containsKey("every_seconds");
            long selectorCount = (hasAfter ? 1 : 0) + (hasAt ? 1 : 0) + (hasEvery ? 1 : 0);
            if (selectorCount != 1) {
                return json(error(ScheduleException.Code.INVALID_SELECTOR,
                        "schedule_create accepts exactly one of after_seconds, at, or every_seconds."));
            }
            if (prompt == null || prompt.trim().isEmpty()) {
                return json(error(ScheduleException.Code.INVALID_PROMPT,
                        "prompt must be non-empty after trimming."));
            }
            try {
                FoldedSchedules folded = ScheduleDomain.foldChanges(
                        ScheduleChangeCodec.extract(session.snapshot()));
                ScheduleId id = ScheduleDomain.allocateScheduleId(folded);
                long now = System.currentTimeMillis();
                ScheduleRecord record;
                if (hasAt) {
                    Object at = arguments.get("at");
                    record = ScheduleDomain.createAtScheduleRecord(id, prompt, at, now);
                } else if (hasAfter) {
                    long after = ((Number) arguments.get("after_seconds")).longValue();
                    record = ScheduleDomain.createAfterScheduleRecord(id, prompt, after, now);
                } else {
                    long every = ((Number) arguments.get("every_seconds")).longValue();
                    record = ScheduleDomain.createEveryScheduleRecord(id, prompt, every, now);
                }
                session.append(ScheduleChangeCodec.EVENT_TYPE,
                        ScheduleChangeCodec.encode(new ScheduleChange.Create(record)));
                return json(view(record, System.currentTimeMillis()));
            } catch (ScheduleException e) {
                return json(error(e.code(), e.getMessage()));
            } catch (Exception e) {
                return json(error(ScheduleException.Code.INTERNAL_ERROR, "The schedule operation failed."));
            }
        }
    }

    static final class ScheduleListTool extends ScheduleToolBase {
        ScheduleListTool(Function<Void, SessionLog> sessionSupplier) {
            super(sessionSupplier);
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.of("schedule_list",
                    "List every active reminder in the current session in creation order, including its "
                            + "exact id, UTC target, scheduled or overdue state, and session-local delivery mode.",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public String invoke(Map<String, Object> arguments, ToolContext ctx) {
            try {
                FoldedSchedules folded = foldOrError();
                long now = System.currentTimeMillis();
                List<Object> views = new ArrayList<>();
                for (ScheduleRecord record : folded.active()) {
                    views.add(view(record, now));
                }
                return json(views);
            } catch (ScheduleException e) {
                return json(error(e.code(), e.getMessage()));
            }
        }
    }

    static final class ScheduleDeleteTool extends ScheduleToolBase {
        ScheduleDeleteTool(Function<Void, SessionLog> sessionSupplier) {
            super(sessionSupplier);
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.of("schedule_delete",
                    "Delete one active reminder in the current session by the exact id returned by "
                            + "schedule_create or schedule_list. Unknown or already-finished ids return deleted false.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "id", Map.of("type", "string",
                                            "description", "Exact session-local schedule id.")),
                            "required", List.of("id")));
        }

        @Override
        public String invoke(Map<String, Object> arguments, ToolContext ctx) {
            SessionLog session = session();
            if (session == null) {
                return json(error(ScheduleException.Code.INTERNAL_ERROR, "No active session."));
            }
            Object idRaw = arguments.get("id");
            if (idRaw == null) {
                return json(error(ScheduleException.Code.INVALID_RULE, "id is required."));
            }
            String idStr = idRaw.toString();
            if (idStr.isEmpty() || !idStr.equals(idStr.trim())) {
                return json(error(ScheduleException.Code.INVALID_RULE,
                        "schedule_delete id must be non-empty without surrounding whitespace."));
            }
            try {
                FoldedSchedules folded = ScheduleDomain.foldChanges(
                        ScheduleChangeCodec.extract(session.snapshot()));
                ScheduleId id = ScheduleId.of(idStr);
                boolean found = folded.active().stream().anyMatch(r -> r.id().equals(id));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", id.value());
                if (!found) {
                    result.put("deleted", false);
                    result.put("code", "schedule_not_found");
                } else {
                    session.append(ScheduleChangeCodec.EVENT_TYPE,
                            ScheduleChangeCodec.encode(new ScheduleChange.Delete(id)));
                    result.put("deleted", true);
                }
                return json(result);
            } catch (ScheduleException e) {
                return json(error(e.code(), e.getMessage()));
            } catch (Exception e) {
                return json(error(ScheduleException.Code.INTERNAL_ERROR, "The schedule operation failed."));
            }
        }
    }
}
