package com.deepseek.dsh.teams;

import java.util.List;
import java.util.Optional;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.core.context.Service;

/**
 * 团队协作能力缝 —— 对应原 Harness 的 {@code Agent Teams}。
 *
 * <p>把一个任务 fan-out 给多个 agent（团队成员）并行执行，再聚合各成员报告
 * 为一个综合结果。成员可为人格/工具集各异的子 agent；聚合策略可插拔。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code DefaultTeamsProvider}（虚拟线程并行 + 摘要聚合）。</li>
 *   <li><b>消费者</b>：{@code TeamTool}（模型可调用的团队派发工具）。</li>
 * </ul>
 *
 * <p>设计模式：策略 + 代理（多子 agent 并行代理）+ 聚合器。
 */
public interface TeamsService extends Service {

    /** 注册一个团队成员（按人格名）。 */
    void registerMember(String name, Agent agent);

    /** 列出已注册成员的人格名。 */
    List<String> memberNames();

    /**
     * 派发一个团队任务：所有成员并行执行，聚合为综合结果。
     *
     * @param task 任务描述
     * @return 团队执行结果（各成员报告 + 聚合摘要）
     */
    TeamResult runTeamTask(String task);

    /** 团队任务结果。 */
    record TeamResult(
            /** 各成员的（名, 报告, 是否成功）三元组。 */
            List<MemberReport> reports,
            /** 聚合摘要。 */
            String summary,
            /** 是否全部成员成功。 */
            boolean allSucceeded
    ) {}

    /** 单个成员的执行报告。 */
    record MemberReport(
            String name,
            String report,
            boolean success,
            Optional<String> error
    ) {}
}
