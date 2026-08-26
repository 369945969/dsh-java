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
# 后端编译 + 单元测试
mvn test

# 前端
cd dsh-frontend && pnpm install && pnpm build
```

## 配置（模型 Key）

后端用环境变量配置模型，脚本从仓库根 `.env` 自动加载（**`.env` 已 gitignore，绝不提交**）：
```bash
cp .env.example .env      # 复制模板
# 编辑 .env：填入模型 Key/端点/模型名
```
| 变量 | 必填 | 说明 |
|------|------|------|
| `DEEPSEEK_API_KEY` | 是 | 模型 API Key |
| `DSH_BASE_URL` | 否 | OpenAI 兼容端点；默认 DeepSeek，glm-5.2 用 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `DSH_MODEL` | 否 | 模型名；默认 `deepseek-chat`，glm-5.2 用 `glm-5.2` |

## 启动方式

提供四种启动方式（脚本在 `scripts/`），覆盖原 Harness 的 `dsh web` / `dsh-jsonrpc-agent` 模式。

### 0. 一键启动前后端（推荐）
构建前端 → 同步到后端静态资源 → 启动 Web 服务。SPA + REST + SSE + WebSocket 同源（默认 8765），
打开浏览器即可用前端对话后端。
```bash
scripts/start.sh [port]        # 打开 http://localhost:8765
```

### 1. RPC 模式（stdio JSON-RPC，进程外运行时）
对应原 Harness 的 `dsh-jsonrpc-agent`。后端作为 stdio newline-delimited JSON-RPC 2.0 服务端，
**stdout 仅承载 JSON-RPC 帧，日志走 stderr**（`logback-rpc.xml`）。SDK 客户端据此干净读取。
```bash
scripts/start-rpc.sh          # 从 .env 读配置；首次自动构建 classpath（install + build-classpath，缓存）
```
方法面（对齐 TS SDK 协议 + Java 便利方法）：
`initialize` / `health` / `session.create` / `session.list` / `session/prompt` / `session.history` / `session.delete` / `session/fork` / `session/compact` / `skill/list` / `skill/get` / `subagent/task` / `team/run` / `shutdown`

### 2. Web 模式（Spring Boot，REST + SSE）
对应原 Harness 的 `dsh web`。前端（自带 React 或用户自有前端）通过 HTTP/SSE 对接。
```bash
scripts/start-web.sh [port]   # 默认 8765
# 浏览器打开 http://localhost:8765
```

### 3. 开发模式（前后端分离）
后端 Web（8765）+ 前端 Vite dev（5173，代理 `/api` 到后端）并行，任一退出则全部退出。
```bash
scripts/dev.sh                # 自动等后端就绪后起前端
# 后端 http://localhost:8765 | 前端 http://localhost:5173
```

### 两种访问协议
- **RPC**：stdio newline-delimited JSON-RPC 2.0（运行时 SDK，进程外子进程通信）。
- **SSE/HTTP + WebSocket**：Web apiproxy 用 SSE（`text/event-stream`、`\n\n` 帧）流式下发；另有 WebSocket `/ws/agent` 支持并发多 session 与会话取消。

## 端到端验证（testcase）

`testcase/` 是端到端测试（非单元测试）：**真正启动服务进程，通过 RPC 和 SSE/WebSocket 外部访问验证后端全部功能**，
按「常用开发模式」分组，方便用户接自己的前端按同一契约验证。

```bash
bash testcase/run-all.sh      # 构建 → RPC E2E → Web/SSE E2E → WebSocket E2E
```

### 分组覆盖（开发模式）

**① 基础对话模式**（RPC `session/prompt` + Web `/send`）
- 基础问候、完整返回响应、自定义模型调用（`initialize` 验证 glm-5.2）

**② 会话与记忆模式**（RPC）
- 记忆保存（`session/history`）、fork agent 保留记忆（`session/fork` 回放父事件）、fork 新 session 无记忆
- 查询 session 列表 + 单 session 状态查询、会话删除管理（`session/delete`）、上下文压缩（`session/compact`）

**③ 技能与编排模式**（RPC）
- 技能发现/加载（`skill/list` + `skill/get`，种子多 skill）、subagent 委派（`subagent/task`）、多 agent 并行编排（`team/run`，虚拟线程 fan-out + 聚合）

**④ 实时通信模式**（Web）
- 流响应：SSE `POST /api/agent/stream`（`session→delta*→done`，逐 token 流式）+ WebSocket `/ws/agent` 流式
- WebSocket 并发多 session（同连接多 sid 交错下发）、会话取消（`cancel` 中断运行回合）
- 前端真实交互（chromium 驱动 SPA：渲染→输入→发送→回复渲染全链路）

### 驱动与脚本
| 文件 | 传输 | 说明 |
|------|------|------|
| `RpcE2e.java` | RPC（stdio JSON-RPC） | 基于 dsh SDK 客户端 `HarnessClient` spawn `start-rpc.sh` 子进程，验证分组 ①②③ |
| `web-e2e.sh` | HTTP + SSE | curl 模拟前端：`health`/`send`/`stream`（分组 ④·HTTP） |
| `ws-e2e.py` | WebSocket | Python `websockets`：并发多 session + 流式 + 取消（分组 ④·WS） |
| `frontend-e2e.py` | 浏览器 | Python `playwright` + 系统 chromium：SPA 渲染→交互→回复渲染（分组 ④·前端，需 playwright+chromium） |
| `run-all.sh` | 全部 | 一键编排：临时 `DSH_DATA_DIR`（hermetic）+ 种子 skill + 四类 E2E（含前端交互） |

单独运行：
```bash
mvn -pl testcase exec:java                                  # RPC 全分组
bash testcase/web-e2e.sh                                     # SSE/HTTP
python3 testcase/ws-e2e.py                                   # WebSocket
```
> 端到端测试需联网调用真实模型（glm-5.2），消耗少量 token；`RpcE2e` 自动种子 2 个技能到 `DSH_DATA_DIR/skills`。

### 运行（旧命令，等价于脚本）
```bash
# 等价于 scripts/start-web.sh
mvn -pl dsh-app spring-boot:run
```

## REST / SSE API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/send` | 一次性对话，返回 agent 回复 + 历史 + token 统计 |
| POST | `/api/agent/stream` | SSE 逐 token 流式对话：`session`→`delta`*→`done([DONE])` 事件流 |
| WS | `/ws/agent` | WebSocket：并发多 session、流式、会话取消 |
| GET | `/api/agent/health` | 健康检查 |

请求体（`send` / `stream`）：
```json
{ "sessionId": null, "message": "你好" }
```
SSE 示例：`curl -N -X POST http://localhost:8765/api/agent/stream -H 'Content-Type: application/json' -d '{"message":"你好"}'`

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

未实现（后续可扩展）：原生 node-addon（Java 侧以虚拟线程 + ProcessRunner 承载，无对应物）、ACP 远程子 agent 的会话事件/子 agent 生命周期通知转发（基础 session.create/session/prompt 桥接已实现）。

> Web SSE/WebSocket 已支持逐 token 流式（基于 `LlmModel.stream`，glm-5.2 推理模型会先下发 `content` token；纯对话回合流式，需工具的回合用 `/api/agent/send`）。
