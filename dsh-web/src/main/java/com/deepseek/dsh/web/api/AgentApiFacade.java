package com.deepseek.dsh.web.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.compaction.CompactionService;
import com.deepseek.dsh.session.Sessions;
import com.deepseek.dsh.session.log.ChatMessage;
import com.deepseek.dsh.session.log.SessionLog;
import com.deepseek.dsh.skill.SkillDefinition;
import com.deepseek.dsh.skill.SkillRenderer;
import com.deepseek.dsh.skill.SkillService;
import com.deepseek.dsh.subagent.DelegationResult;
import com.deepseek.dsh.subagent.SubagentService;
import com.deepseek.dsh.teams.DefaultTeamsProvider;

/**
 * Agent 能力 API 共享门面 —— RPC 服务端（{@code DshRpcServer}）与 Web apiproxy
 * （{@link ApiproxyController}）共用的方法实现，避免两套重复 handler。
 *
 * <p>承载<b>传输无关</b>的纯 Context+Agent 操作（compact / delete / skill.get /
 * subagent.task / team.run）。传输相关的方法（fork 涉及各自的事件 sink、prompt 涉及
 * 各自的编排/记录）保留在各自端，不在此门面。
 *
 * <p>设计模式：门面（Facade）—— 单点实现，多传输复用。
 */
public final class AgentApiFacade {

    private final Context ctx;
    private final Agent agent;

    public AgentApiFacade(Context ctx, Agent agent) {
        this.ctx = ctx;
        this.agent = agent;
    }

    /** session.compact —— 对会话历史触发上下文压缩。 */
    public Map<String, Object> sessionCompact(String sessionId, int maxTokens) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", sessionId);
        Sessions sessions = ctx.require(Sessions.class);
        SessionLog slog = sessions.getOrCreate(SessionId.of(sessionId));
        List<ChatMessage> msgs = slog.deriveMessages().messages();
        r.put("before", msgs.size());
        CompactionService comp = ctx.get(CompactionService.class).orElse(null);
        if (comp == null) {
            r.put("error", "compaction service not registered");
            return r;
        }
        List<ChatMessage> compacted = comp.compact(msgs, maxTokens);
        r.put("after", compacted.size());
        r.put("compacted", compacted.size() < msgs.size());
        return r;
    }

    /** session.delete —— 删除会话（活跃表 + 持久化文件）。 */
    public Map<String, Object> sessionDelete(String sessionId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", sessionId);
        if (sessionId == null || sessionId.isEmpty()) {
            r.put("deleted", false);
            return r;
        }
        r.put("deleted", ctx.require(Sessions.class).delete(SessionId.of(sessionId)));
        return r;
    }

    /** skill.get —— 加载并渲染单个技能（<skill_content> 块）。 */
    public Map<String, Object> skillGet(String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        java.util.Optional<SkillService> opt = ctx.get(SkillService.class);
        if (opt.isEmpty()) {
            r.put("found", false);
            r.put("error", "skill service not registered");
            return r;
        }
        java.util.Optional<SkillDefinition> def = opt.get().get(name, null);
        if (def.isEmpty()) {
            r.put("found", false);
            r.put("name", name);
            return r;
        }
        r.put("found", true);
        r.put("name", name);
        r.put("rendered", SkillRenderer.render(def.get()));
        return r;
    }

    /** subagent.task —— 委派子任务给子 agent。 */
    public Map<String, Object> subagentTask(String sessionId, String task) {
        Map<String, Object> r = new LinkedHashMap<>();
        SubagentService sub = ctx.get(SubagentService.class).orElse(null);
        if (sub == null) {
            r.put("error", "subagent service not registered");
            r.put("success", false);
            return r;
        }
        DelegationResult res = sub.delegate(
                SessionId.of(sessionId), ScopeKey.random(), ctx, agent, task);
        r.put("report", res.report());
        r.put("success", res.success());
        if (res.childSessionId() != null) r.put("childSessionId", res.childSessionId());
        r.put("forwardedEventCount", res.forwardedEventCount());
        return r;
    }

    /** team.run —— 多 agent 并行编排（临时团队，主 agent 扮演两名成员）。 */
    public Map<String, Object> teamRun(String task) {
        DefaultTeamsProvider teams = new DefaultTeamsProvider();
        teams.setContext(ctx);
        teams.registerMember("reviewer", agent);
        teams.registerMember("tester", agent);
        var res = teams.runTeamTask(task);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("summary", res.summary());
        r.put("memberCount", res.reports().size());
        r.put("allSucceeded", res.allSucceeded());
        return r;
    }

    /** shutdown —— 优雅关闭协议握手（返回 ok；实际退出由各传输层决定）。 */
    public Map<String, Object> shutdown() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "ok");
        return r;
    }
}
