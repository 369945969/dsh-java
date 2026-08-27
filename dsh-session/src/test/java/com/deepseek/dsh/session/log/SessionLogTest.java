package com.deepseek.dsh.session.log;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.brand.SessionId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionLog 事件溯源 + 消息投影测试。
 */
class SessionLogTest {

    @Test
    void 追加事件递增序号与快照() {
        var log = new SessionLog(SessionId.of("s1"));
        assertEquals(-1, log.lastSeq());
        assertEquals(0, log.size());
        log.append(SessionEvent.Type.USER_MESSAGE, SessionEvent.Payload.text("hi"));
        log.append(SessionEvent.Type.ASSISTANT_MESSAGE, SessionEvent.Payload.text("hello"));
        assertEquals(2, log.size());
        assertEquals(1, log.lastSeq());
        assertEquals(2, log.snapshot().size());
    }

    @Test
    void 投影用户与助手消息() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append(SessionEvent.Type.USER_MESSAGE, SessionEvent.Payload.text("Hello"));
        log.append(SessionEvent.Type.ASSISTANT_MESSAGE, SessionEvent.Payload.text("Hello!"));
        var proj = log.deriveMessages();
        assertEquals(2, proj.messages().size());
        assertEquals(ChatMessage.Role.USER, proj.messages().get(0).role());
        assertEquals("Hello", proj.messages().get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, proj.messages().get(1).role());
    }

    @Test
    void 助手分块积累为一条消息() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append(SessionEvent.Type.USER_MESSAGE, SessionEvent.Payload.text("q"));
        log.append(SessionEvent.Type.ASSISTANT_CHUNK, SessionEvent.Payload.text("Hel"));
        log.append(SessionEvent.Type.ASSISTANT_CHUNK, SessionEvent.Payload.text("lo"));
        var proj = log.deriveMessages();
        assertEquals(2, proj.messages().size());
        assertEquals("Hello", proj.messages().get(1).content());
    }

    @Test
    void 工具调用与结果配对投影() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append(SessionEvent.Type.USER_MESSAGE, SessionEvent.Payload.text("List files"));
        log.append(SessionEvent.Type.ASSISTANT_MESSAGE, SessionEvent.Payload.text("Invoke tool"));
        log.append(SessionEvent.Type.TOOL_CALL,
                SessionEvent.Payload.toolCall("bash", "call-1", java.util.Map.of("cmd", "ls")));
        log.append(SessionEvent.Type.TOOL_RESULT,
                SessionEvent.Payload.toolResult("call-1", "file1\nfile2"));
        var proj = log.deriveMessages();
        // user + assistant(文本) + assistant(toolCall) + tool(结果)
        assertEquals(4, proj.messages().size());
        assertEquals(ChatMessage.Role.ASSISTANT, proj.messages().get(1).role());
        // toolCall 挂在 assistant 消息的 toolCalls 上
        var asstWithCall = proj.messages().get(2);
        assertEquals(ChatMessage.Role.ASSISTANT, asstWithCall.role());
        assertEquals(1, asstWithCall.toolCalls().size());
        assertEquals("call-1", asstWithCall.toolCalls().get(0).id());
        assertEquals("bash", asstWithCall.toolCalls().get(0).name());
        // 工具结果作为独立 tool 消息
        assertEquals("call-1", proj.messages().get(3).toolCallId());
        assertEquals("file1\nfile2", proj.messages().get(3).content());
    }

    @Test
    void 控制事件不投影为消息() {
        var log = new SessionLog(SessionId.of("s1"));
        log.append(SessionEvent.Type.TURN_START, SessionEvent.Payload.text(""));
        log.append(SessionEvent.Type.STEP_START, SessionEvent.Payload.text(""));
        log.append(SessionEvent.Type.USER_MESSAGE, SessionEvent.Payload.text("hi"));
        log.append(SessionEvent.Type.TURN_END, SessionEvent.Payload.text(""));
        assertEquals(1, log.deriveMessages().messages().size());
    }
}
