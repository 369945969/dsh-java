package com.deepseek.dsh.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.deepseek.dsh.session.log.SessionEvent;

/**
 * 调度变更编解码器 —— 在 {@code schedule/change} 会话事件数据与
 * {@link ScheduleChange} 值对象之间转换。
 *
 * <p>设计模式：编解码器（Codec）—— 在持久边界做结构化转换。
 */
public final class ScheduleChangeCodec {

    public static final String EVENT_TYPE = "schedule/change";
    public static final String MARKER_KEY = "schedule_change";
    public static final String MARKER_VALUE = "v1";

    private ScheduleChangeCodec() {
    }

    public static Map<String, Object> encode(ScheduleChange change) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put(MARKER_KEY, MARKER_VALUE);
        payload.put("version", change.version());
        payload.put("operation", change.operation());
        switch (change) {
            case ScheduleChange.Create c -> payload.put("schedule", recordToMap(c.schedule()));
            case ScheduleChange.Delete d -> payload.put("id", d.id().value());
            case ScheduleChange.Dispatch d -> {
                payload.put("id", d.id().value());
                if (d.acceptedAt() != null) {
                    payload.put("acceptedAt", d.acceptedAt());
                }
            }
        }
        return payload;
    }

    public static ScheduleChange decode(Map<String, Object> data) {
        if (data == null) {
            throw badPayload("schedule/change payload must be an object");
        }
        if (!MARKER_VALUE.equals(data.get(MARKER_KEY))) {
            throw badPayload("not a schedule/change event");
        }
        Object version = data.get("version");
        if (!(version instanceof Number n) || n.intValue() != 1) {
            throw badPayload("schedule/change version must be 1");
        }
        String operation = stringOrNull(data.get("operation"));
        return switch (operation) {
            case "create" -> new ScheduleChange.Create(decodeRecord(data.get("schedule")));
            case "delete" -> new ScheduleChange.Delete(requireId(data.get("id")));
            case "dispatch" -> {
                ScheduleId id = requireId(data.get("id"));
                if (data.containsKey("acceptedAt")) {
                    yield new ScheduleChange.Dispatch(id, decodeInstant(stringOrNull(data.get("acceptedAt"))));
                }
                yield new ScheduleChange.Dispatch(id, null);
            }
            default -> throw badPayload("schedule/change operation must be create, delete, or dispatch");
        };
    }

    public static List<ScheduleChange> extract(List<SessionEvent> events) {
        List<ScheduleChange> changes = new ArrayList<>();
        for (SessionEvent e : events) {
            if (!EVENT_TYPE.equals(e.type())) {
                continue;
            }
            if (e.data() == null) {
                continue;
            }
            if (!MARKER_VALUE.equals(e.data().get(MARKER_KEY))) {
                continue;
            }
            changes.add(decode(e.data()));
        }
        return changes;
    }

    public static boolean isScheduleChange(SessionEvent event) {
        return EVENT_TYPE.equals(event.type())
                && event.data() != null
                && MARKER_VALUE.equals(event.data().get(MARKER_KEY));
    }

    @SuppressWarnings("unchecked")
    private static ScheduleRecord decodeRecord(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw badPayload("schedule record must be an object");
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        String kind = stringOrNull(m.get("kind"));
        ScheduleId id = requireId(m.get("id"));
        String prompt = requirePrompt(m.get("prompt"));
        return switch (kind) {
            case "after" -> {
                Object after = m.get("afterSeconds");
                if (!(after instanceof Number n) || n.longValue() <= 0) {
                    throw badPayload("afterSeconds must be a positive safe integer");
                }
                yield new ScheduleRecord.After(id, prompt, n.longValue(), decodeInstant(stringOrNull(m.get("scheduledAt"))));
            }
            case "at" -> new ScheduleRecord.At(id, prompt, decodeInstant(stringOrNull(m.get("scheduledAt"))));
            case "every" -> {
                Object every = m.get("everySeconds");
                if (!(every instanceof Number n) || n.longValue() < ScheduleDomain.MIN_EVERY_INTERVAL_SECONDS) {
                    throw badPayload("everySeconds must be a safe integer of at least "
                            + ScheduleDomain.MIN_EVERY_INTERVAL_SECONDS);
                }
                yield new ScheduleRecord.Every(id, prompt, n.longValue(), decodeInstant(stringOrNull(m.get("scheduledAt"))));
            }
            default -> throw badPayload("v1 schedule kind must be \"after\", \"at\", or \"every\"");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recordToMap(ScheduleRecord record) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", record.id().value());
        m.put("kind", record.kind());
        m.put("prompt", record.prompt());
        m.put("scheduledAt", record.scheduledAt());
        switch (record) {
            case ScheduleRecord.After a -> m.put("afterSeconds", a.afterSeconds());
            case ScheduleRecord.Every e -> m.put("everySeconds", e.everySeconds());
            case ScheduleRecord.At ignored -> { }
        }
        return m;
    }

    private static ScheduleId requireId(Object value) {
        String raw = stringOrNull(value);
        if (raw == null || raw.isEmpty() || !raw.equals(raw.trim())) {
            throw badPayload("schedule id must be a non-empty string without surrounding whitespace");
        }
        return ScheduleId.of(raw);
    }

    private static String requirePrompt(Object value) {
        String raw = stringOrNull(value);
        if (raw == null || raw.isEmpty() || !raw.equals(raw.trim())) {
            throw badPayload("prompt must be non-empty and already trimmed");
        }
        return raw;
    }

    private static String decodeInstant(Object value) {
        String raw = stringOrNull(value);
        if (raw == null) {
            throw badPayload("scheduledAt must be a canonical four-digit-year RFC 3339 UTC instant");
        }
        return ScheduleDomain.canonicalizeInstant(raw);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static ScheduleException badPayload(String message) {
        return ScheduleException.corruptLog(message);
    }
}
