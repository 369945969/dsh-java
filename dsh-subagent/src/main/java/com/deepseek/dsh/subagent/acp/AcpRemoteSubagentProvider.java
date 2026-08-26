package com.deepseek.dsh.subagent.acp;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.exception.CapabilityException;
import com.deepseek.dsh.sdk.client.HarnessClient;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentEvent;
import com.deepseek.dsh.subagent.SubagentService;

/**
 * ACP 远程子 agent 提供者 —— 对应原 Harness 的 {@code subagent-acp} / 远程桥。
 *
 * <p>把子任务委派给一个<b>外部 agent 进程</b>（如 Claude Code、Codex，或另一个
 * {@code dsh-jsonrpc-agent} 运行时），通过 newline-delimited JSON-RPC over stdio
 * 通信。与 {@code ForkInProcessProvider} 互补：后者在进程内 fork 本地 agent，
 * 本提供者把执行外化到一个独立进程/运行时（能力缝的策略切换点）。
 *
 * <p>会话生命周期：每次 {@link #delegate} 复用一个池化的远程会话（按人格名分组），
 * 任务描述前置 {@link Agent#systemPrompt()} 作为人格前导；委派结束保留会话以供后续
 * 委派延续上下文，{@link #close} 时统一销毁子进程。
 *
 * <p>设计模式：策略的具体实现 + 远程代理（Remote Proxy，{@link HarnessClient} 即线代理）。
 */
public final class AcpRemoteSubagentProvider
        extends AbstractCapabilityPlugin<SubagentService>
        implements SubagentService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpRemoteSubagentProvider.class);

    /** 远程运行时启动命令（如 {@code dsh-jsonrpc-agent} 或 {@code claude --acp}）。 */
    private final String runtimeCommand;
    /** 复用的远程客户端，按人格名分组（同一人格复用同一会话）。 */
    private final ConcurrentMap<String, RemoteSession> sessions = new ConcurrentHashMap<>();

    public AcpRemoteSubagentProvider(String runtimeCommand) {
        this.runtimeCommand = runtimeCommand;
    }

    @Override
    protected Class<SubagentService> serviceType() {
        return SubagentService.class;
    }

    @Override
    public DelegationResult delegate(SessionId parentSessionId, ScopeKey parentScopeKey,
                                    Context ctx, Agent agent, String task) {
        String persona = agent.name();
        String taskPreview = task.length() > 80 ? task.substring(0, 80) + "…" : task;
        try {
            RemoteSession session = sessions.computeIfAbsent(persona, this::openSession);
            String childSid = session.sessionId();

            // 生命周期通知：委派开始（转发给父 agent 的 EventBus）
            ctx.events().emit(new SubagentEvent(SubagentEvent.Kind.SPAWNED,
                    parentSessionId, childSid, persona, 0, taskPreview));

            // 人格前导：把 agent 的系统提示作为前导注入，让远程 agent 承载该人格
            String fullTask = agent.systemPrompt() == null || agent.systemPrompt().isBlank()
                    ? task
                    : "【人格: " + persona + "】\n" + agent.systemPrompt() + "\n\n任务:\n" + task;
            String reply = session.client().prompt(session.sessionId(), fullTask).join().reply();

            // 会话事件转发：拉取远程子会话历史投影，统计消息事件数
            int eventCount = 0;
            try {
                var history = session.client().history(session.sessionId()).join();
                eventCount = history.messages().size();
            } catch (Exception ignore) {
                // 历史拉取失败不阻断委派，事件数记 0
            }

            String reportPreview = reply.length() > 80 ? reply.substring(0, 80) + "…" : reply;
            ctx.events().emit(new SubagentEvent(SubagentEvent.Kind.COMPLETED,
                    parentSessionId, childSid, persona, eventCount, reportPreview));

            return new DelegationResult(reply, true, childSid, eventCount);
        } catch (Exception e) {
            log.warn("ACP 远程子 agent 委派失败 ({}): {}", persona, e.toString());
            ctx.events().emit(new SubagentEvent(SubagentEvent.Kind.FAILED,
                    parentSessionId, null, persona, 0, e.getMessage()));
            return new DelegationResult("远程子 agent 执行失败: " + e.getMessage(), false);
        }
    }

    /** 懒打开一个远程会话。 */
    private RemoteSession openSession(String persona) {
        try {
            HarnessClient client = new HarnessClient(runtimeCommand);
            String sessionId = client.createSession().join();
            log.debug("打开远程子 agent 会话 (persona={}, sid={})", persona, sessionId);
            return new RemoteSession(client, sessionId);
        } catch (Exception e) {
            throw new CapabilityException("subagent",
                    "无法启动远程 agent 运行时: " + runtimeCommand, e);
        }
    }

    /** 销毁所有远程子进程。 */
    @Override
    public void close() {
        sessions.values().forEach(s -> {
            try {
                s.client().close();
            } catch (Exception e) {
                log.debug("关闭远程子 agent 进程失败: {}", e.toString());
            }
        });
        sessions.clear();
    }

    private record RemoteSession(HarnessClient client, String sessionId) {}
}
