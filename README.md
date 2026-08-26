# DeepSeek Harness (Java 重写版)

> 原项目 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的 Java 重写。
> 后端 Java 21 + Spring Boot 3，插件优先架构（Cordis 等价的 Context/Plugin），
> 借鉴 [AgentScope](https://github.com/agentscope-ai/agentscope) 的 ReAct loop / Toolkit / Middleware / Event Bus / Permission 思想用 Java 实现，
> 保留原 React 前端风格。

## 架构概览

DeepSeek Harness 的核心理念是 **"一切皆插件"**：模型适配器、工具注册表、会话日志、
甚至 agent loop 本身都是挂载在其他插件旁边的插件，且每次注册都是可逆的。

本重写以 Java 实现了同样的分层，并显式标注了所用的设计模式：

```
dsh-java/
├── pom.xml                      # 父 POM（多模块，37 个模块）
├── dsh-core/                    # 插件/Context 基座（Cordis 等价）、Event Bus、Middleware 链、Branded IDs、共用子进程执行器
├── dsh-session/                 # 追加式 SessionEvent 日志（事件溯源）、deriveMessages 投影、JSONL 持久化、会话标题/遥测
├── dsh-session-sqlite/          # SQLite 会话持久化 + FTS5 全文/语义会话查询
├── dsh-tools/                   # 工具注册表、执行管线、JSON Schema 装配、ToolArgs/ToolSchema 构建器
├── dsh-llm/                     # LLM 能力缝、DeepSeek 适配器、流式/重试/Token 计量、会话标题提供程序
├── dsh-agent/                   # Agent 接口、ReAct loop、turn/step/round 生命周期（可注入中间件管线）
├── dsh-capability-shell/         # shell/bash 能力缝 + 本地提供者 + bash 工具
├── dsh-capability-fs/            # 文件系统能力缝 + 本地提供者 + read/write/edit/glob/grep 工具
├── dsh-capability-web/           # Web 搜索/抓取能力缝 + DeepSeek 搜索 + HTTP 抓取 + web_search/web_fetch 工具
├── dsh-terminal/                 # 持久终端/PTY 能力缝 + bash 终端 + terminal 工具
├── dsh-compaction/               # 上下文压缩能力缝 + 摘要 provider + 工具结果裁剪器
├── dsh-subagent/                 # subagent 委派能力缝 + in-process fork + task 工具
├── dsh-goal/                     # 同会话目标持久化 + goal 命令 + goal-round
├── dsh-plan/                     # 计划模式（日志化状态 + reviewed exit）
├── dsh-workflow/                 # 工作流能力缝 + 虚拟线程引擎 + 异步任务 + Ralph 循环
├── dsh-code-runtime/             # 代码运行时能力缝 + Python 提供者
├── dsh-lsp/                      # LSP 能力缝 + stdio provider（定义跳转/诊断）
├── dsh-interaction/             # 审批/权限/命令/ask-user
├── dsh-mcp/                     # MCP 客户端桥接（注册外部 MCP 服务器工具）
├── dsh-sandbox/                 # 沙箱能力缝 + LocalSandboxProvider（Linux bwrap + macOS Seatbelt + Windows 降级）
├── dsh-jobs/                    # 后台任务运行时（虚拟线程）+ job 工具（output/list/kill）
├── dsh-todo/                    # todo_write 工具（任务清单管理，按会话隔离）
├── dsh-guard/                   # 工具中间件：重复调用提醒 + 超时策略
├── dsh-context/                 # 上下文注入：AGENTS.md 加载 + 时间注入 + @file 引用解析
├── dsh-credentials/             # 凭据/授权能力缝 + 本地 .env 提供者
├── dsh-settings/                # 用户设置缝 + 文件设置提供者（JSON 命名空间）
├── dsh-storage/                  # 通用存储中心缝 + 内存/文件后端（原子持久化）
├── dsh-spill/                    # 超大工具输出外溢存储缝 + 本地文件后端 + 外溢策略中间件
├── dsh-skill/                    # 技能目录/加载器缝 + 文件系统提供者 + skill 工具 + skill_content 渲染
├── dsh-subprocess/               # 托管进程组缝 + 本地提供者（环境清洗 + PATH 解析，复用 ProcessRunner）
├── dsh-teams/                    # Agent Teams 协作缝 + 默认提供者（虚拟线程并行 fan-out + 聚合）+ team 工具
├── dsh-telemetry/                # 遥测能力缝 (OpenTelemetry) + no-op/日志后端 + 工具中间件
├── dsh-acp/                     # Automation-only ACP 服务器（JSON-RPC over stdio）
├── dsh-sdk/                     # JSON-RPC 协议 + 客户端 + 服务端（进程外运行时 SDK）
├── dsh-web/                     # Spring Boot Web 服务、REST API、SPA 托管
├── dsh-app/                     # 启动引导、Profile/Bundle 组合、Spring Boot 入口
└── dsh-frontend/                # React + Vite 前端（保留原 --dsw-* 风格）
```

## 核心概念与设计模式

| 模块 | 关键概念 | 设计模式 |
|------|---------|---------|
| dsh-core | Context（服务仓库）/ Plugin / 可逆副作用 | 注册表、组合、装饰器、Disposable |
| dsh-core | EventBus（emit/waterfall/parallel/serial） | 观察者 + 责任链 |
| dsh-core | Middleware 链（借鉴 AgentScope） | 责任链 + 装饰器 |
| dsh-core | Branded\<T,Tag\>（不透明 ID） | 值对象 |
| dsh-session | SessionLog（追加日志）+ deriveMessages | 事件溯源 + 投影 |
| dsh-tools | Tool / ToolRegistry / ToolPipeline | 命令、注册表、责任链 |
| dsh-llm | LlmModel / DeepSeekLlmAdapter / RetryLlmModel | 适配器、策略、装饰器 |
| dsh-llm | TokenMeter | 观察者（聚合统计） |
| dsh-agent | ReActAgentLoop（turn→step→round） | 模板方法 + 状态机 + 策略 |
| dsh-capability-* | 能力缝（定义/提供者/消费者） | 策略 + SPI + 工厂 |
| dsh-interaction | Approval / Permission / Commands | 责任链 + 代理 + 命令 |
| dsh-mcp | StdioMcpClient | 适配器（MCP ↔ 内部工具）+ 桥接 |
| dsh-app | BaseBundle / Profile | 构建器 + 组合 + 抽象工厂 |
| dsh-terminal | 持久终端/PTY 能力缝 | 策略 + SPI |
| dsh-compaction | 上下文压缩（摘要 + 裁剪） | 策略 + 装饰器 |
| dsh-subagent | subagent 委派（in-process fork） | 策略 + 代理 |
| dsh-goal | 目标持久化 + goal-round | 状态机 + 注册表 |
| dsh-plan | 计划模式 | 状态机 + 备忘录 |
| dsh-session-sqlite | SQLite 持久化 + FTS 查询 | 仓储 + 适配器 |
| dsh-workflow | 异步工作流引擎（虚拟线程） | 策略 + 命令调度 |
| dsh-code-runtime | 代码运行时（Python） | 策略 + SPI |
| dsh-lsp | 语言服务器（LSP stdio） | 适配器 |
| dsh-acp | ACP 服务器（自动化协议） | 命令分发器 |
| dsh-sdk | JSON-RPC 协议 + 客户端/服务端 | 远程代理 + 前端控制器 |

### 能力缝（Capability Seam）

一个可替换能力由三部分组成（永不可只有其一）：
1. **服务定义** —— 拥有 `ctx.<key>` 与词汇类型的接口（如 `ShellCapability`、`FsCapability`、`LlmModel`）。
2. **服务提供者** —— 具体实现（如 `BashLocalProvider`、`FsLocalProvider`、`DeepSeekLlmAdapter`）。
3. **消费者** —— 面向模型的工具，注入该服务（如 `BashTool` 注入 `ShellCapability`）。

**切换提供者即可整体迁移执行世界**（本地 ↔ 沙箱），这正是能力缝的核心价值。

### Agent 循环层级

- **turn** —— 一次已准入输入的排空，在模型+工具停止或终止策略介入时结束。
- **step** —— 一次模型请求加上其响应引起的工具执行；一个 turn 有零或多个 step。
- **round** —— 包含一个 turn 的外层策略迭代。

不变式：**"模型可见 ⟺ 已记录"** —— 所有发送给模型的消息都从 `SessionLog` 投影得到。

## 快速开始

### 环境要求
- JDK 21
- Maven 3.9+
- Node.js 22+ / pnpm（前端）

### 构建
```bash
# 后端编译 + 测试
mvn test

# 前端
cd dsh-frontend && pnpm install && pnpm build
```

### 运行
```bash
# 设置 DeepSeek API Key
export DEEPSEEK_API_KEY=sk-xxxx
# 可选：指定模型
export DSH_MODEL=deepseek-chat

# 启动后端（含 Web 服务）
mvn -pl dsh-app spring-boot:run

# 浏览器打开 http://localhost:8765
```

### 开发模式（前后端分离）
```bash
# 终端 1：后端
mvn -pl dsh-app spring-boot:run

# 终端 2：前端 dev server（代理 /api 到后端）
cd dsh-frontend && pnpm dev
# 打开 http://localhost:5173
```

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/send` | 发送消息，返回 agent 回复 + 历史 + token 统计 |
| GET | `/api/agent/health` | 健康检查 |

请求体：
```json
{ "sessionId": null, "message": "你好" }
```

## 与原项目的关系

本重写聚焦**核心 + 最小可用**及**高级能力**范围，覆盖原 TypeScript Harness 的主要能力域。
已实现（37 个模块，181 个 Java 文件，56 个测试全绿）：

**核心层**：插件基座（Context/Plugin + 可逆副作用 + 作用域遮蔽 + Event Bus 四模式 + Middleware 链）、
agent loop（ReAct + turn/step/round，模板方法 + 状态机，可注入中间件管线）、事件溯源会话日志（"模型可见⟺已记录"）、
工具注册表与执行管线（命令 + 责任链 + ToolArgs/ToolSchema 构建器）、DeepSeek LLM 适配器（流式 + 重试装饰器 + Token 计量 + 会话标题提供程序）。

**能力层**：bash/shell、文件系统（read/write/edit/glob/grep）、持久终端/PTY（跨调用保持进程状态）、
代码运行时（Python 子进程）、LSP（语言服务器，定义跳转/诊断）、Web 搜索/抓取（DeepSeek 搜索 + HTTP 抓取）、
沙箱（Linux bwrap + macOS Seatbelt + Windows 降级）、托管子进程组（环境清洗 + PATH 解析，复用 ProcessRunner）。

**交互层**：审批（一次性人类确认）、权限预设（allow/deny/ask 三态链）、斜杠命令（不经模型派发）、
ask-user（结构化提问）、计划模式（日志化状态 + reviewed exit）、目标（同会话持久化 + goal-round）、
后台任务运行时（虚拟线程 + job 工具）、任务清单（todo_write，按会话隔离）、
重复调用提醒 + 超时策略中间件、AGENTS.md 加载 + 时间注入 + @file 引用解析。

**数据/存储层**：凭据/授权（本地 .env + 环境变量叠加）、用户设置（JSON 命名空间，文件持久化）、
通用存储中心（内存/文件后端，原子写入）、超大工具输出外溢存储（私有会话作用域文件 + 外溢策略中间件）、
技能目录/加载器（文件系统提供者 + frontmatter 解析 + skill 工具 + skill_content 渲染）、
SQLite 持久化 + FTS5 全文检索、上下文压缩（LLM 摘要 + 工具结果裁剪）。

**集成层**：subagent 委派（in-process fork + 谱系关联，可切换 ACP 远程子 agent 桥接 Claude Code/Codex 等外部 agent 进程）、
工作流引擎（虚拟线程异步任务 + Ralph 循环）、Agent Teams 多 agent 协作（并行 fan-out + 聚合 + team 工具）、
遥测能力缝（OpenTelemetry 风格 Span/Metric/Counter + no-op/日志后端 + 工具中间件）、
MCP 客户端桥接（注册外部工具服务器）、ACP 服务器（自动化协议）、
SDK（JSON-RPC 协议 + 客户端 + 服务端）。

**前端**：React + Vite，保留原 `--dsw-*` 设计令牌的深色对话式风格，对接 Java 后端 REST API。

未实现（后续可扩展）：原生 node-addon（Java 侧以虚拟线程 + ProcessRunner 承载，无对应物）、ACP 远程子 agent 的高级协议特性。
