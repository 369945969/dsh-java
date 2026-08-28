package com.deepseek.dsh.app.bundle;

import java.nio.file.Path;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.agent.react.ReActAgentLoop;
import com.deepseek.dsh.capability.code.CodeRuntime;
import com.deepseek.dsh.capability.code.python.PythonCodeRuntime;
import com.deepseek.dsh.capability.fs.FsCapability;
import com.deepseek.dsh.capability.fs.local.FsLocalProvider;
import com.deepseek.dsh.capability.fs.tool.EditTool;
import com.deepseek.dsh.capability.fs.tool.GlobTool;
import com.deepseek.dsh.capability.fs.tool.GrepTool;
import com.deepseek.dsh.capability.fs.tool.ReadTool;
import com.deepseek.dsh.capability.fs.tool.WriteTool;
import com.deepseek.dsh.capability.shell.ShellCapability;
import com.deepseek.dsh.capability.shell.local.BashLocalProvider;
import com.deepseek.dsh.capability.shell.tool.BashTool;
import com.deepseek.dsh.capability.terminal.TerminalCapability;
import com.deepseek.dsh.capability.terminal.bash.BashTerminalProvider;
import com.deepseek.dsh.capability.terminal.tool.TerminalTool;
import com.deepseek.dsh.compaction.BasicCompactionProvider;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;
import com.deepseek.dsh.core.context.Plugin;
import com.deepseek.dsh.core.util.PluginRunner;
import com.deepseek.dsh.goal.GoalService;
import com.deepseek.dsh.interaction.approval.ApprovalService;
import com.deepseek.dsh.interaction.command.CommandRegistry;
import com.deepseek.dsh.interaction.permission.PermissionService;
import com.deepseek.dsh.interaction.question.UserQuestionService;
import com.deepseek.dsh.llm.adapter.LlmModel;
import com.deepseek.dsh.llm.adapter.LlmModelPlugin;
import com.deepseek.dsh.llm.deepseek.DeepSeekLlmAdapter;
import com.deepseek.dsh.llm.meter.TokenMeter;
import com.deepseek.dsh.llm.retry.RetryLlmModel;
import com.deepseek.dsh.plan.PlanModeService;
import com.deepseek.dsh.session.SessionManager;
import com.deepseek.dsh.session.persistence.JsonlSessionStore;
import com.deepseek.dsh.session.persistence.SessionStore;
import com.deepseek.dsh.tools.registry.ToolRegistry;
import com.deepseek.dsh.workflow.WorkerThreadWorkflowProvider;

/**
 * 基础包（dsh-base 等价物）—— 组装 agent 运行所需的核心插件集。
 *
 * <p>对应原 Harness 的 {@code dsh-base}：模型适配器、工具注册表、持久化、
 * 沙箱、审批、设置等第一层。本类以构建器风格把各能力缝的具体提供者组合在一起。
 *
 * <p>设计模式：构建器（Builder）+ 组合（Composite）。
 */
public final class BaseBundle {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Path dataDir;

    public BaseBundle(String apiKey, String model, Path dataDir) {
        this(apiKey, "https://api.deepseek.com", model, dataDir);
    }

    public BaseBundle(String apiKey, String baseUrl, String model, Path dataDir) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.dataDir = dataDir;
    }

    /**
     * 将所有核心插件挂载到上下文，并返回一个已装配的 {@link Agent}。
     */
    public Agent assemble(Context ctx, PluginRunner runner) throws Exception {
        // 会话持久化
        SessionStore store = new JsonlSessionStore(dataDir.resolve("sessions"));
        runner.add(new SessionManager(store));

        // 工具注册表
        ToolRegistry toolRegistry = new ToolRegistry();
        runner.add(toolRegistry);

        // 模型配置（运行时可变，页面配置覆盖环境变量初值）
        var modelConfig = new com.deepseek.dsh.llm.config.ModelConfig(apiKey, baseUrl, this.model);

        // LLM 模型（DeepSeek/OpenAI 兼容 + 重试）；适配器每次请求动态读取 modelConfig
        var adapter = new DeepSeekLlmAdapter(apiKey, baseUrl, this.model);
        adapter.setConfig(modelConfig);
        LlmModel llm = new RetryLlmModel(adapter);
        runner.add(new LlmModelPlugin(llm));

        // Token 计量
        runner.add(new TokenMeter());

        // 交互能力（headless 自动模式）
        runner.add(new ApprovalService.AutoApprovalService());
        runner.add(new PermissionService.DefaultPermissionService());
        runner.add(new UserQuestionService.AutoUserQuestionService());
        runner.add(new CommandRegistry());

        // 高级能力插件
        runner.add(new BasicCompactionProvider());  // 上下文压缩
        runner.add(new com.deepseek.dsh.subagent.fork.ForkInProcessProvider()); // subagent 委派
        runner.add(new GoalService());               // 目标持久化
        runner.add(new PlanModeService());           // 计划模式
        runner.add(new WorkerThreadWorkflowProvider()); // 工作流（虚拟线程）

        // 后台任务 + 上下文注入
        var jobService = new com.deepseek.dsh.capability.jobs.JobService();
        runner.add(jobService);
        runner.add(new com.deepseek.dsh.context.instructions.AgentInstructionsPlugin(dataDir));
        runner.add(new com.deepseek.dsh.context.time.TimeContextPlugin());
        runner.add(new com.deepseek.dsh.context.reference.FileReferenceService());

        // 遥测能力缝（默认 no-op；可换 LoggingTelemetryProvider 启用 OTel 风格日志）
        var telemetry = new com.deepseek.dsh.telemetry.NoopTelemetryProvider();
        runner.add(telemetry);

        // 团队协作能力缝
        var teams = new com.deepseek.dsh.teams.DefaultTeamsProvider();
        runner.add(teams);

        // 凭据/设置/存储/子进程/外溢 能力缝（本地提供者，需在 start 前加入以注册到上下文）
        var credentials = new com.deepseek.dsh.credentials.LocalCredentialsProvider(
                dataDir.resolve(".env"));
        runner.add(credentials);
        runner.add(new com.deepseek.dsh.settings.FileSettingsProvider(
                dataDir.resolve("settings.json")));
        runner.add(new com.deepseek.dsh.storage.LocalStorageProvider(dataDir));
        runner.add(new com.deepseek.dsh.subprocess.LocalSubprocessProvider());
        var spillStore = new com.deepseek.dsh.spill.LocalSpillStore(dataDir.resolve("spill"));
        runner.add(spillStore);

        // 技能注册表能力缝（先加入，start 后再挂文件系统提供者）
        var skills = new com.deepseek.dsh.skill.SkillRegistry();
        runner.add(skills);
        // 技能目录注入：把 model-invocable 技能摘要注入系统提示，让模型能发现并调用 skill 工具
        runner.add(new com.deepseek.dsh.skill.SkillCatalogPlugin(skills));

        // 启动插件（所有能力缝须在此之前加入，apply 才会注册到上下文）
        runner.start(ctx);

        // 模型配置中心（多自定义模型档案，持久化到 dataDir/model-config.json）
        var modelStore = new com.deepseek.dsh.llm.config.ModelProfileStore(
                dataDir.resolve("model-config.json"), modelConfig, apiKey, baseUrl, this.model);
        ctx.register(com.deepseek.dsh.llm.config.ModelConfig.class, modelConfig);
        ctx.register(com.deepseek.dsh.llm.config.ModelProfileStore.class, modelStore);

        // 消息反馈侧车（点赞/点踩，持久化到 dataDir/message-feedback.json）
        var feedback = new com.deepseek.dsh.feedback.MessageFeedbackService(
                dataDir, com.deepseek.dsh.feedback.MessageFeedbackService.DEFAULT_MAX_NOTE_BYTES,
                ctx.require(com.deepseek.dsh.session.Sessions.class));
        ctx.register(com.deepseek.dsh.feedback.MessageFeedbackService.class, feedback);
        ctx.track(feedback::dispose);

        // 注册具体能力提供者 + 工具
        ShellCapability shell = new BashLocalProvider();
        toolRegistry.register(new BashTool(shell));

        FsCapability fs = new FsLocalProvider();
        toolRegistry.register(new ReadTool(fs));
        toolRegistry.register(new WriteTool(fs));
        toolRegistry.register(new EditTool(fs));
        toolRegistry.register(new GlobTool(fs));
        toolRegistry.register(new GrepTool(fs));

        // 持久终端
        TerminalCapability terminal = new BashTerminalProvider();
        toolRegistry.register(new TerminalTool(terminal));

        // Web 搜索/抓取
        var searchProvider = new com.deepseek.dsh.capability.web.search.DeepSeekSearchProvider(apiKey);
        var fetchProvider = new com.deepseek.dsh.capability.web.fetch.HttpFetchProvider();
        toolRegistry.register(new com.deepseek.dsh.capability.web.tool.WebSearchTool(searchProvider));
        toolRegistry.register(new com.deepseek.dsh.capability.web.tool.WebFetchTool(fetchProvider));

        // 后台任务工具
        toolRegistry.register(new com.deepseek.dsh.capability.jobs.JobTool(jobService));

        // todo 工具
        toolRegistry.register(new com.deepseek.dsh.todo.TodoWriteTool());

        // 大输出外溢策略中间件（spillStore 已在 start 前注册）
        var spillPolicy = new com.deepseek.dsh.spill.SpillPolicy(65_536L, spillStore);

        // 技能：挂文件系统提供者 + skill 工具（skills 已注册为 SkillService）
        skills.registerProvider(new com.deepseek.dsh.skill.FilesystemSkillProvider(
                null, java.util.List.of(), dataDir.toString()));
        toolRegistry.register(new com.deepseek.dsh.skill.SkillTool(skills));

        // 团队派发工具
        toolRegistry.register(new com.deepseek.dsh.teams.TeamTool(teams));

        // 目标工具
        toolRegistry.register(new com.deepseek.dsh.goal.GoalTool(
                ctx.require(com.deepseek.dsh.goal.Goals.class)));

        // 工作流 + Ralph 工具
        toolRegistry.register(new com.deepseek.dsh.workflow.WorkflowTool(
                ctx.require(com.deepseek.dsh.workflow.WorkflowService.class)));
        toolRegistry.register(new com.deepseek.dsh.workflow.RalphTool(
                ctx.require(com.deepseek.dsh.workflow.WorkflowService.class)));

        // 装配 agent（带外溢策略 + 遥测中间件）
        return new ReActAgentLoop(
                "DeepSeek-Harness",
                defaultSystemPrompt(),
                llm,
                toolRegistry,
                new com.deepseek.dsh.tools.pipeline.ToolPipeline(toolRegistry,
                        java.util.List.of(spillPolicy,
                                new com.deepseek.dsh.telemetry.TelemetryMiddleware(telemetry))));
    }

    private String defaultSystemPrompt() {
        return """
                你是 DeepSeek Harness（dsh）—— 一个强大的软件工程助手。
                你可以使用以下工具完成编程任务：
                - bash：执行 shell 命令
                - terminal：管理持久终端会话（跨调用保持进程状态）
                - read/write/edit/glob/grep：文件读写与搜索
                - web_search/web_fetch：网络搜索与抓取
                - job：后台任务管理
                - todo_write：任务清单管理
                - goal：会话目标管理
                - workflow/ralph：后台工作流与 Ralph 循环
                - skill：按名加载技能（注入技能指令）
                - team：把任务并行派发给团队成员，返回综合结果
                请优先使用工具获取信息，给出简洁、准确的回答。
                面对复杂任务时可设定目标或进入计划模式。
                """;
    }
}
