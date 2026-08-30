package com.deepseek.dsh.agent;

import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.agent.react.ReActAgentLoop;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.interaction.approval.ApprovalService;
import com.deepseek.dsh.interaction.command.CommandRegistry;
import com.deepseek.dsh.interaction.permission.PermissionService;
import com.deepseek.dsh.interaction.question.UserQuestionService;
import com.deepseek.dsh.llm.adapter.LlmResponse;
import com.deepseek.dsh.session.SessionManager;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.persistence.JsonlSessionStore;
import com.deepseek.dsh.tools.registry.ToolRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReAct 循环端到端集成测试 —— 用 Mock 模型验证 turn→step→stop 全流程。
 */
class ReActAgentLoopTest {

    @Test
    void 单步回复不调用工具() throws Exception {
        MockLlmModel model = new MockLlmModel().enqueueText("你好！我是 DeepSeek Harness。");

        ToolRegistry tools = new ToolRegistry();
        Context ctx = Context.root();
        tools.apply(ctx);
        new SessionManager(new JsonlSessionStore(Files.createTempDirectory("dsh-test")))
                .apply(ctx);
        new ApprovalService.AutoApprovalService().apply(ctx);
        new PermissionService.DefaultPermissionService().apply(ctx);
        new UserQuestionService.AutoUserQuestionService().apply(ctx);
        new CommandRegistry().apply(ctx);

        ReActAgentLoop agent = new ReActAgentLoop(model, new com.deepseek.dsh.tools.pipeline.ToolPipeline(tools, java.util.List.of()), tools);
        SessionId sessionId = SessionId.of("test-1");
        String reply = agent.run(sessionId, ScopeKey.random(), ctx, "你好");

        assertEquals("你好！我是 DeepSeek Harness。", reply);
    }

    @Test
    void 工具调用后继续推理() throws Exception {
        // 第一步：模型发起工具调用
        ChatMessage.ToolCall call = new ChatMessage.ToolCall("call-1", "echo", "{\"text\":\"hello\"}");
        MockLlmModel model = new MockLlmModel()
                .enqueue(new LlmResponse("", List.of(call), null, "tool_calls"))
                .enqueueText("你说了: hello");

        ToolRegistry tools = new ToolRegistry();
        // 注册一个 echo 工具
        tools.register(new com.deepseek.dsh.tools.registry.Tool() {
            @Override
            public com.deepseek.dsh.tools.schema.ToolSchema schema() {
                return com.deepseek.dsh.tools.schema.ToolSchema.of(
                        "echo", "回显输入", java.util.Map.of(
                                "type", "object",
                                "properties", java.util.Map.of(
                                        "text", java.util.Map.of("type", "string")),
                                "required", List.of("text")));
            }

            @Override
            public String invoke(java.util.Map<String, Object> arguments,
                                 com.deepseek.dsh.tools.registry.ToolContext ctx) {
                return "echo: " + arguments.get("text");
            }
        });

        Context ctx = Context.root();
        tools.apply(ctx);
        new SessionManager(new JsonlSessionStore(Files.createTempDirectory("dsh-test2")))
                .apply(ctx);
        new ApprovalService.AutoApprovalService().apply(ctx);
        new PermissionService.DefaultPermissionService().apply(ctx);
        new UserQuestionService.AutoUserQuestionService().apply(ctx);
        new CommandRegistry().apply(ctx);

        ReActAgentLoop agent = new ReActAgentLoop(model, new com.deepseek.dsh.tools.pipeline.ToolPipeline(tools, java.util.List.of()), tools);
        String reply = agent.run(SessionId.of("test-2"), ScopeKey.random(), ctx, "说 hello");

        assertEquals("你说了: hello", reply);
    }
}
