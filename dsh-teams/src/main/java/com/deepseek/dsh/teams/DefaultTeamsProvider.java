package com.deepseek.dsh.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;

/**
 * 默认团队协作提供者 —— 对应原 Harness 团队协作的并行编排。
 *
 * <p>用 Java 21 虚拟线程（{@link Executors#newVirtualThreadPerTaskExecutor}）并行启动
 * 所有成员 agent 执行同一任务，收集各成员报告，再聚合为一份综合摘要。
 * 任一成员失败不阻断其他成员（部分降级），符合「尽力聚合」语义。
 *
 * <p>成员 agent 在真实插件上下文中执行（{@link #apply} 捕获 context），确保能取到
 * Sessions 等已注册服务；进程内临时实例可用 {@link #setContext} 注入。
 *
 * <p>聚合策略可插拔点：子类可覆盖 {@link #aggregate} 改用 LLM 摘要等高级策略；
 * 默认实现为结构化拼接，保证无外部依赖即可工作。
 *
 * <p>设计模式：策略的具体实现 + 模板方法（并行 fan-out 骨架抽出，聚合可覆盖）+ 聚合器。
 */
public class DefaultTeamsProvider
        extends AbstractCapabilityPlugin<TeamsService>
        implements TeamsService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTeamsProvider.class);

    private final ConcurrentMap<String, Agent> members = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 成员 agent 执行所用的真实上下文（apply 时捕获或 setContext 注入）。 */
    private volatile Context context;

    @Override
    public Disposable apply(Context ctx) {
        this.context = ctx;
        return super.apply(ctx);
    }

    /** 注入上下文（供进程内临时实例，如 RPC 的 team/run）。 */
    public void setContext(Context ctx) {
        this.context = ctx;
    }

    @Override
    protected Class<TeamsService> serviceType() {
        return TeamsService.class;
    }

    @Override
    public void registerMember(String name, Agent agent) {
        if (members.putIfAbsent(name, agent) != null) {
            throw new IllegalStateException("Team member already exists: " + name);
        }
    }

    @Override
    public List<String> memberNames() {
        return List.copyOf(members.keySet());
    }

    @Override
    public TeamResult runTeamTask(String task) {
        if (members.isEmpty()) {
            return new TeamResult(List.of(), "(No team members)", true);
        }

        // 并行 fan-out：每个成员一个虚拟线程
        List<Future<MemberReport>> futures = new ArrayList<>();
        members.forEach((name, agent) ->
                futures.add(executor.submit(() -> runMember(this, name, agent, task))));

        List<MemberReport> reports = new ArrayList<>();
        for (Future<MemberReport> f : futures) {
            try {
                reports.add(f.get());
            } catch (Exception e) {
                log.warn("Team member recycle failed: {}", e.toString());
            }
        }
        return aggregate(task, reports);
    }

    private static MemberReport runMember(DefaultTeamsProvider self, String name, Agent agent, String task) {
        SessionId sid = SessionId.of("team-" + name + "-" + java.util.UUID.randomUUID());
        Context ctx = self.context != null ? self.context : Context.root();
        try {
            String report = agent.run(sid, ScopeKey.random(), ctx, task);
            return new MemberReport(name, report, true, Optional.empty());
        } catch (Exception e) {
            log.warn("Team member {} execution failed: {}", name, e.toString());
            return new MemberReport(name, "", false, Optional.of(e.getMessage()));
        }
    }

    /**
     * 聚合各成员报告为综合摘要。默认结构化拼接 + 统计；
     * 子类可覆盖为 LLM 摘要等高级策略。
     */
    protected TeamResult aggregate(String task, List<MemberReport> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("团队任务: ").append(truncate(task, 120)).append('\n');
        sb.append("成员 ").append(reports.size()).append(" 名:\n");
        long ok = 0;
        for (MemberReport r : reports) {
            sb.append("- ").append(r.name()).append(": ");
            if (r.success()) {
                sb.append(truncate(r.report(), 200));
                ok++;
            } else {
                sb.append("失败 (").append(r.error().orElse("未知")).append(")");
            }
            sb.append('\n');
        }
        sb.append("成功 ").append(ok).append('/').append(reports.size());
        return new TeamResult(List.copyOf(reports), sb.toString(), ok == reports.size());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
