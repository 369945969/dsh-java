package com.deepseek.dsh.subagent.fork;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentEvent;
import com.deepseek.dsh.subagent.SubagentService;

/**
 * 进程内 fork 提供者 —— 对应原 Harness 的 {@code subagent-fork-in-process}。
 *
 * <p><b>重构后</b>：继承 {@link AbstractCapabilityPlugin}，消除样板。
 *
 * <p>设计模式：策略的具体实现 + 代理（子 agent 代理执行）+ 模板方法。
 */
public final class ForkInProcessProvider
        extends AbstractCapabilityPlugin<SubagentService>
        implements SubagentService {

    private static final Logger log = LoggerFactory.getLogger(ForkInProcessProvider.class);

    @Override
    protected Class<SubagentService> serviceType() {
        return SubagentService.class;
    }

    @Override
    public DelegationResult delegate(SessionId parentSessionId, ScopeKey parentScopeKey,
                                    Context ctx, Agent agent, String task) {
        // 创建子会话
        Sessions sessions = ctx.require(Sessions.class);
        SessionId childSessionId = SessionId.of(UUID.randomUUID().toString());
        // 子作用域键（深度 +1）
        ScopeKey childScope = ScopeKey.random();
        String childSid = childSessionId.value();
        String persona = agent.name();
        String taskPreview = task.length() > 80 ? task.substring(0, 80) + "…" : task;

        log.debug("Delegating subtask to sub-session {} (parent {}): {}",
                childSessionId, parentSessionId, taskPreview);

        // 生命周期通知：委派开始
        ctx.events().emit(new SubagentEvent(SubagentEvent.Kind.SPAWNED,
                parentSessionId, childSid, persona, 0, taskPreview));

        try {
            String report = agent.run(childSessionId, childScope, ctx, task);
            // 会话事件转发：统计子会话已记录的事件数
            int eventCount = sessions.getOrCreate(childSessionId).snapshot().size();
            String reportPreview = report.length() > 80 ? report.substring(0, 80) + "…" : report;
            ctx.events().emit(new SubagentEvent(SubagentEvent.Kind.COMPLETED,
                    parentSessionId, childSid, persona, eventCount, reportPreview));
            return new DelegationResult(report, true, childSid, eventCount);
        } catch (Exception e) {
            log.warn("Sub-agent execution failed: {}", e.toString());
            ctx.events().emit(new SubagentEvent(SubagentEvent.Kind.FAILED,
                    parentSessionId, childSid, persona, 0, e.getMessage()));
            return new DelegationResult("Subtask execution failed: " + e.getMessage(), false, childSid, 0);
        }
    }
}
