package com.deepseek.dsh.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.tools.registry.Tool;
import com.deepseek.dsh.tools.registry.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 调度工具测试 —— 覆盖 schedule_create/list/delete 的成功与错误路径。
 */
class ScheduleToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SessionLog newSession() {
        return new SessionLog(SessionId.of("ses-tools"));
    }

    private static ToolContext ctx(SessionLog session) {
        return new ToolContext(session.sessionId(), null, null);
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void createAfterReturnsView() throws Exception {
        SessionLog session = newSession();
        Tool tool = ScheduleTools.createTool(v -> session);
        String result = tool.invoke(Map.of("prompt", "hello", "after_seconds", 60), ctx(session));
        JsonNode node = json(result);
        assertEquals("after", node.get("kind").asText());
        assertEquals("hello", node.get("prompt").asText());
        assertEquals("scheduled", node.get("state").asText());
        assertEquals("session-local", node.get("deliveryMode").asText());
        assertEquals(60, node.get("afterSeconds").asInt());
        assertEquals(1, session.snapshot().size());
    }

    @Test
    void createRequiresExactlyOneSelector() throws Exception {
        SessionLog session = newSession();
        Tool tool = ScheduleTools.createTool(v -> session);
        String result = tool.invoke(Map.of("prompt", "hi"), ctx(session));
        JsonNode node = json(result);
        assertEquals("invalid_selector", node.get("code").asText());
        assertEquals(0, session.snapshot().size());
    }

    @Test
    void createRejectsEmptyPrompt() throws Exception {
        SessionLog session = newSession();
        Tool tool = ScheduleTools.createTool(v -> session);
        String result = tool.invoke(Map.of("prompt", "   ", "after_seconds", 60), ctx(session));
        JsonNode node = json(result);
        assertEquals("invalid_prompt", node.get("code").asText());
    }

    @Test
    void createEveryRejectsHighFrequency() throws Exception {
        SessionLog session = newSession();
        Tool tool = ScheduleTools.createTool(v -> session);
        String result = tool.invoke(Map.of("prompt", "tick", "every_seconds", 60), ctx(session));
        JsonNode node = json(result);
        assertEquals("frequency_too_high", node.get("code").asText());
    }

    @Test
    void createAtFromOffsetString() throws Exception {
        SessionLog session = newSession();
        Tool tool = ScheduleTools.createTool(v -> session);
        String result = tool.invoke(Map.of("prompt", "wake", "at", "2099-12-31T13:00:00Z"), ctx(session));
        JsonNode node = json(result);
        assertEquals("at", node.get("kind").asText());
        assertEquals("2099-12-31T13:00:00.000Z", node.get("scheduledAt").asText());
    }

    @Test
    void listReturnsAllActive() throws Exception {
        SessionLog session = newSession();
        Tool create = ScheduleTools.createTool(v -> session);
        create.invoke(Map.of("prompt", "a", "after_seconds", 3600), ctx(session));
        create.invoke(Map.of("prompt", "b", "after_seconds", 7200), ctx(session));

        Tool list = ScheduleTools.listTool(v -> session);
        String result = list.invoke(Map.of(), ctx(session));
        JsonNode node = json(result);
        assertTrue(node.isArray());
        assertEquals(2, node.size());
    }

    @Test
    void listEmptyWhenNoSchedules() throws Exception {
        SessionLog session = newSession();
        Tool list = ScheduleTools.listTool(v -> session);
        String result = list.invoke(Map.of(), ctx(session));
        JsonNode node = json(result);
        assertTrue(node.isArray());
        assertTrue(node.isEmpty());
    }

    @Test
    void deleteRemovesActiveSchedule() throws Exception {
        SessionLog session = newSession();
        Tool create = ScheduleTools.createTool(v -> session);
        String created = create.invoke(Map.of("prompt", "a", "after_seconds", 3600), ctx(session));
        String id = json(created).get("id").asText();

        Tool delete = ScheduleTools.deleteTool(v -> session);
        String result = delete.invoke(Map.of("id", id), ctx(session));
        JsonNode node = json(result);
        assertEquals(id, node.get("id").asText());
        assertTrue(node.get("deleted").asBoolean());

        Tool list = ScheduleTools.listTool(v -> session);
        assertTrue(json(list.invoke(Map.of(), ctx(session))).isEmpty());
    }

    @Test
    void deleteUnknownReturnsNotFound() throws Exception {
        SessionLog session = newSession();
        Tool delete = ScheduleTools.deleteTool(v -> session);
        String result = delete.invoke(Map.of("id", "schedule-999"), ctx(session));
        JsonNode node = json(result);
        assertFalse(node.get("deleted").asBoolean());
        assertEquals("schedule_not_found", node.get("code").asText());
    }

    @Test
    void deleteRejectsBlankId() throws Exception {
        SessionLog session = newSession();
        Tool delete = ScheduleTools.deleteTool(v -> session);
        String result = delete.invoke(Map.of("id", "  "), ctx(session));
        JsonNode node = json(result);
        assertEquals("invalid_rule", node.get("code").asText());
    }

    @Test
    void createThenDeleteKeepsIdSeen() throws Exception {
        SessionLog session = newSession();
        Tool create = ScheduleTools.createTool(v -> session);
        create.invoke(Map.of("prompt", "a", "after_seconds", 3600), ctx(session));
        String id = json(create.invoke(Map.of("prompt", "b", "after_seconds", 3600), ctx(session))).get("id").asText();

        Tool delete = ScheduleTools.deleteTool(v -> session);
        delete.invoke(Map.of("id", id), ctx(session));

        Tool list = ScheduleTools.listTool(v -> session);
        assertEquals(1, json(list.invoke(Map.of(), ctx(session))).size());
    }

    @Test
    void allRegistersThreeTools() {
        assertEquals(3, ScheduleTools.all(v -> newSession()).size());
    }
}
