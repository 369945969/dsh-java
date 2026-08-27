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
├── pom.xml                      # 父 POM（多模块，41 个模块）
├── dsh-core/                    # 插件/Context 基座（Cordis 等价）、Event Bus、Middleware 链、Branded IDs、共用子进程执行器
├── dsh-session/                 # 追加式 SessionEvent 日志（事件溯源）、deriveMessages 投影、JSONL 持久化、会话标题/遥测
├── dsh-session-sqlite/          # SQLite 会话持久化 + FTS5 全文/语义会话查询
├── dsh-tools/                   # 工具注册表、执行管线、JSON Schema 装配、ToolArgs/ToolSchema 构建器
├── dsh-llm/                     # LLM 能力缝、DeepSeek 适配器、流式/重试/Token 计量、会话标题提供程序、模型配置中心（多档案 + 页面配置 + reasoning_content 捕获）
├── dsh-agent/                   # Agent 接口、ReAct loop、turn/step/round 生命周期（可注入中间件管线）、TurnObserver（事件映射）、setSystemPrompt（预设切换）
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
├── dsh-settings/                # 用户设置缝 + 文件设置提供者（JSON 命名空间，持久化到 ~/.dsh/settings.json）
├── dsh-storage/                  # 通用存储中心缝 + 内存/文件后端（原子持久化）
├── dsh-spill/                    # 超大工具输出外溢存储缝 + 本地文件后端 + 外溢策略中间件
├── dsh-skill/                    # 技能目录/加载器缝 + 文件系统提供者 + skill 工具 + skill_content 渲染
├── dsh-subprocess/               # 托管进程组缝 + 本地提供者（环境清洗 + PATH 解析，复用 ProcessRunner）
├── dsh-attachment/               # 内容寻址附件存储（sha256 去重 + 策略 + SPI）
├── dsh-workspace/                # 工作区实体注册表（路径归一化、会话归集、JSON 持久化到 ~/.dsh/workspaces.json）
├── dsh-feedback/                 # 会话反馈：/feedback 命令（仅日志事件）+ 消息级反馈（正面/负面 + 备注，乐观并发）
├── dsh-schedule/                 # 会话级持久提醒（after/at/every，事件日志折叠重放，会话本地投递）
├── dsh-teams/                    # Agent Teams 协作缝 + 默认提供者（虚拟线程并行 fan-out + 聚合）+ team 工具
├── dsh-telemetry/                # 遥测能力缝 (OpenTelemetry) + no-op/日志后端 + 工具中间件
├── dsh-acp/                     # Automation-only ACP 服务器（JSON-RPC over stdio）
├── dsh-sdk/                     # JSON-RPC 协议 + 客户端 + 服务端（进程外运行时 SDK）
├── dsh-web/                     # Spring Boot Web 服务、apiproxy JSON-RPC 网关（settings/llm/credentials/agentPreset/commands/pluginInventory/messageFeedback）、SPA 托管、WebSocket 下行流（mux + host）、Session 导出
├── dsh-app/                     # 启动引导、Profile/Bundle 组合、Spring Boot 入口、RPC/ACP/CLI 入口、前端静态资源
├── frontend/                    # 原版 deepseek-harness Cordis 前端（原封复制，原版构建链；dist+启动快照已托管于 dsh-app/static）
└── testcase/                    # 端到端测试（16 项 TS 测试 + run-e2e.sh）
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
| dsh-llm | TokenMeter + reasoning_content 捕获 | 观察者（聚合统计） |
| dsh-agent | ReActAgentLoop（turn→step→round）+ TurnObserver + setSystemPrompt | 模板方法 + 状态机 + 策略 + 观察者 |
| dsh-capability-* | 能力缝（定义/提供者/消费者） | 策略 + SPI + 工厂 |
| dsh-interaction | Approval / Permission / Commands | 责任链 + 代理 + 命令 |
| dsh-mcp | StdioMcpClient | 适配器（MCP ↔ 内部工具）+ 桥接 |
| dsh-app | BaseBundle / Profile | 构建器 + 组合 + 抽象工厂 |
| dsh-web | ApiproxyController（apiproxy JSON-RPC 网关） | 前端控制器 + 策略 |
| dsh-web | WorkspaceRegistry（持久化到 workspaces.json） | 注册表 + 仓储 |
| dsh-web | ApiproxyDownlinkRegistry（mux/host WS 下行流） | 观察者 + 推送 |

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
- Node.js 22+ / pnpm（前端，运行时无需）

### 一键启动
```bash
scripts/start.sh [port]        # 打开 http://localhost:8765
```
脚本自动加载 `.env`、首次构建 classpath（缓存）、启动 Spring Boot Web 服务。
前端（原版 Cordis SPA）已预构建托管于 `dsh-app/src/main/resources/static`，无需额外构建。

### 配置（模型 Key）

支持**两种配置方式并存**，页面配置覆盖环境变量初值：

#### 1. 环境变量 / .env
```bash
cp .env.example .env      # 复制模板，填入 DEEPSEEK_API_KEY
```
| 变量 | 必填 | 说明 |
|------|------|------|
| `DEEPSEEK_API_KEY` | 是 | 模型 API Key |
| `DSH_BASE_URL` | 否 | OpenAI 兼容端点；glm-5.2 用 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `DSH_MODEL` | 否 | 模型名；默认 `deepseek-chat`，glm-5.2 用 `glm-5.2` |

#### 2. 页面配置（Web 设置页）
打开 http://localhost:8765 → 设置 → 模型：
- 添加自定义模型档案（显示名 / 模型名 / API Key / 端点）
- 切换当前活跃模型（即时生效）
- 删除模型档案
- 模型清单持久化到 `~/.dsh/model-config.json`（每个档案含 route + models 数组，跨重启存活）

## Web 设置页功能

### 模型管理（设置 → 模型）
- **添加/编辑/删除模型**：支持自定义 OpenAI 兼容提供方（DeepSeek、GLM、Qwen、OpenAI 等）
- **即时切换**：活跃模型切换后下一回合即生效
- **持久化**：模型档案 + 路由 + models 数组持久化到 `~/.dsh/model-config.json`
- **route 字段**：每个档案持久化 route（llm-pi-ai namespace 的 settingsPath），跨重启自动重建映射
- **schema 信封**：返回 schemastery 兼容的 `llm-pi-ai` namespace schema（providers dict → profile {apiKeyEnv, api, baseURL, models[]}）
- **即时回显**：保存后推送 `settings/document-updated` + `llm/adapters-updated` remote 事件，前端立即刷新
- **删除按钮**：namespace 视图设 `user=providers, base={}` 使 `removable=true`

### Agent 预设（设置 → Agent 预设）
- **三个系统预设**：standard（标准）、code（PTC 模式）、headless（无界面）
- **切换预设**：select 后全局切换 agent 系统提示（`Agent.setSystemPrompt`），下一回合生效
- **默认预设**：写入 `agent-presets` settings namespace 的 `default` 字段，`agentPreset.list` 的 `isDefault` 反映用户选择
- **用户预设**：复制到 `~/.dsh/presets/{id}.yml`，支持 read/copy/openDocument/remove
- **限制**：单 agent 架构下为全局切换（非按会话重组）

### 插件清单（设置 → Plugins）
- `pluginInventory/list` 返回 40 个已装配模块（对应原版 TS 的 @deepseek-ai/dsh-* 插件包）
- `dynamicCordisRunner/inventory` → `[]`（无动态包）、`syncInspectManifest` → `null`
- `commands/list` → `[]`（无命令目录）
- `messageFeedback/list` → `{items:[]}`（无反馈 sidecar）

### 欢迎声明（onboarding）
- `ui-onboarding` namespace 返回 + `welcomeNoticeVersion` 持久化到 `~/.dsh/settings.json`
- 点击「继续」确认后记录版本，刷新不再弹出

### 打开配置文件
- `settings.openDocument` → `{opened:true}` + best-effort `Desktop.open(~/.dsh/model-config.json)`

## 会话与工作区

### 会话标题
- **自动生成**：取首条用户消息前 40 字符（对应 `BasicSessionTitleProvider` 策略）
- **投影推送**：`session.prompt` 时推送 `session/projection` mux 帧（key=title）+ session.history 携带 `projections` 块
- **侧边栏即时显示**：mux WS 连接时推送所有会话的 title 投影，刷新后无需点击即可显示标题
- **手动重命名**：`session.rename` 持久化到内存（覆盖自动生成）

### 工作区
- **自动分组**：未分组会话发送消息时自动按 `yyyy-MM-dd-HH` 创建/复用时段工作区
- **持久化**：工作区 + 归档集持久化到 `~/.dsh/workspaces.json`，跨重启存活
- **管理操作**：create / rename / delete / archiveSession / insertBefore / insertSessionBefore / list
- **下行帧推送**：workspace-changed / workspace-removed / archived-sessions-changed

### 会话操作
- **session.create** → 返回 sessionId
- **session.prompt** → 异步运行 agent turn（ReAct 循环，含工具调用）
- **session.history** → 分页返回事件 + projections 块（title 投影）
- **session.fork** → 复制父会话全部事件到新会话（保留记忆）
- **session.cancel** → 中断运行中的 agent turn 线程 + cancelledSessions 标记 + turn/end reason=aborted
- **session.rename** → 更新会话标题
- **session.list** → 返回所有会话（含 title / blank / running / updatedAt）
- **session.export** → `GET /api/session.export?sessionId=…` → ZIP 下载（.jsonl）

### 事件信封（apiproxy → 前端）
- `surfaceOp:'append'` 标记在 `user/message`、`assistant/message`、`tool/result`（surface 事件）
- `tool/result` content block 为 `{type:'tool-result', toolCallId, content:[{type:'text',text}], isError:false}`
- `assistant/chunk` 支持 `reasoning-delta`（思考内容）+ `text-delta`（回复内容）
- `turn/end` reason: `complete` 或 `aborted`（取消时）

## 启动方式

| 脚本 | 入口类 | 传输/模式 | 说明 |
|------|--------|-----------|------|
| `start.sh` | `DshApplication` | Web（SPA+REST+SSE+WS） | 一键构建前端并启动 Web 服务（推荐） |
| `start-web.sh` | `DshApplication` | Spring Boot REST+SSE | 纯后端 Web 服务（默认 8765） |
| `dev.sh` | `DshApplication`+Vite | 前后端分离 dev | 后端 8765 + 前端 5173 并行 |
| `start-rpc.sh` | `DshRpcServer` | stdio JSON-RPC | 运行时 SDK 全功能（进程外子进程） |
| `start-acp.sh` | `DshAcpServer` | stdio JSON-RPC | ACP 自动化最小方法集 |
| `start-cli.sh` | `DshRepl` | 终端 REPL | 交互式对话（斜杠命令） |

> 所有脚本共享首次 classpath 构建缓存（`dsh-app/target/rpc-cp.txt`），从仓库根 `.env` 自动加载模型配置。

### 下行流（WebSocket）
- `/api/events.mux` → mux WS（session/event + session/projection + session/subscribed 帧）
- `/api/events.host` → host WS（host/session-added / session-status / workspace-changed / archived-sessions-changed / remote-event 帧）
- mux WS 连接时自动推送所有会话的 title 投影

## REST / SSE API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/send` | 一次性对话，返回 agent 回复 + 历史 + token 统计 |
| POST | `/api/agent/stream` | SSE 逐 token 流式对话：`session`→`delta`*→`done([DONE])` |
| WS | `/ws/agent` | WebSocket：并发多 session、流式、会话取消 |
| WS | `/api/events.mux` | mux 下行流：session/event + session/projection 帧 |
| WS | `/api/events.host` | host 下行流：host/* 帧 |
| GET | `/api/agent/health` | 健康检查 |
| GET | `/api/session.export?sessionId=…` | 导出会话日志 ZIP |
| GET | `/api/config/models` | 列出全部模型档案（Key 脱敏）+ 当前活跃 ID |
| POST | `/api/config/models` | 添加自定义模型档案 |
| PUT | `/api/config/models/{id}` | 更新模型档案 |
| DELETE | `/api/config/models/{id}` | 删除模型档案 |
| PUT | `/api/config/models/active` | 切换当前活跃模型（即时生效） |

## 端到端测试

```bash
bash testcase/run-e2e.sh          # 启动后端 + 运行 16 项 TS 测试 + 报告通过情况
```

### 16 项测试用例

| # | 测试名 | 验证内容 |
|---|--------|---------|
| 1 | 基础agent会话返回 | session.create + prompt + history → assistant 回复 |
| 2 | 流读取返回(SSE) | POST /api/agent/stream → SSE chunks |
| 3 | 完整性推理完返回 | 推理任务（123×456）→ 完整推理 + 回复 |
| 4 | 多agent协同 | subagent/task + team/run 调用 |
| 5 | WebSocket连接 | /ws/agent action:prompt → done 帧 |
| 6 | session多轮对话(记忆) | 第一条消息记住信息 → 第二条回忆验证 |
| 7 | fork保留记忆 | session.fork → 子会话继承父历史 → 提问验证记忆 |
| 8 | fork不保留记忆 | 新 session.create → 无记忆 |
| 9 | 取消会话和管理会话 | session.cancel → accepted |
| 10 | 查询所有会话 | session.list → items 数组 |
| 11 | 查询问答记录(分页) | session.history → events 数组 |
| 12 | 创建session返回sessionId | session.create → 非空 sessionId + 出现在 list |
| 13 | 根据sessionId查看历史 | session.history → user/message + assistant/message + surfaceOp + projections |
| 14 | 继续发送消息到同一sessionId | 同一 session 连续两条消息 → 第二条回复记得第一条 |
| 15 | 查看session列表(字段完整性) | session.list → title/updatedAt/running/blank/sessionId 均存在 |
| 16 | 工具调用验证 | prompt 触发 read 工具 → tool/call + tool/result 事件 + tool-result content block 类型 |

> 测试文件：`testcase/e2e.ts`（Node 25+ 原生 TS，无外部依赖）
> 运行脚本：`testcase/run-e2e.sh`（自动启动后端 + 运行测试 + 报告通过情况）

## 单元测试

196 个单元测试覆盖 28 个后端模块的核心纯逻辑（无网络、无外部依赖）。

```bash
mvn test                          # 运行全部单元测试
```

## 数据目录

| 文件 | 说明 |
|------|------|
| `~/.dsh/model-config.json` | 模型档案（id/displayName/apiKey/baseUrl/model/route/models） |
| `~/.dsh/settings.json` | 用户设置（ui-onboarding/agent-presets 等命名空间） |
| `~/.dsh/workspaces.json` | 工作区注册表（workspaces + archived sessions） |
| `~/.dsh/presets/*.yml` | 用户自定义 agent 预设 |
| `~/.dsh/sessions/*.jsonl` | 会话事件日志（事件溯源，JSONL 追加） |

> 所有数据文件已 gitignore，绝不提交 API Key。

## 与原项目的关系

已实现（41 个模块，221+ Java 源文件，196 个单元测试全绿）：

**核心层**：插件基座（Context/Plugin + 可逆副作用 + 作用域遮蔽 + Event Bus 四模式 + Middleware 链）、
agent loop（ReAct + turn/step/round + TurnObserver 事件映射 + setSystemPrompt 预设切换）、
事件溯源会话日志、工具注册表与执行管线、DeepSeek LLM 适配器（流式 + 重试 + Token 计量 + reasoning_content 捕获 + 模型配置中心）。

**能力层**：bash/shell、文件系统（read/write/edit/glob/grep）、持久终端/PTY、
代码运行时（Python）、LSP、Web 搜索/抓取、沙箱、托管子进程组。

**交互层**：审批、权限预设、斜杠命令、ask-user、计划模式、目标、后台任务、任务清单、
重复调用提醒 + 超时策略、AGENTS.md + 时间注入 + @file 引用解析。

**数据/存储层**：凭据/授权、用户设置（JSON 命名空间，~/.dsh/settings.json 持久化）、
通用存储中心、外溢存储、技能目录/加载器、SQLite + FTS5、上下文压缩、
内容寻址附件、工作区注册表（~/.dsh/workspaces.json 持久化 + 归档 + 自动时段分组）。

**集成层**：subagent 委派（in-process fork + ACP 桥接）、工作流引擎、Agent Teams、
遥测、MCP 客户端、ACP 服务器、SDK。

**Web 层**：Spring Boot + apiproxy JSON-RPC 网关（settings/llm/credentials/agentPreset/commands/pluginInventory/messageFeedback）、
WebSocket mux/host 下行流（session/event + session/projection + host/* 帧）、
SPA 托管、Session 导出 ZIP、WorkspaceRegistry 持久化。

**前端**：React + Vite，保留原 `--dsw-*` 设计令牌的深色对话式风格，对接 Java 后端 apiproxy。

### 架构差异说明

Java 重写版与原版 TS Harness 的主要架构差异：

| 领域 | TS 原版 | Java 重写 | 说明 |
|------|---------|-----------|------|
| 插件加载 | Cordis Loader（动态 cordis.yml 组合） | Spring DI + 静态 BaseBundle | Java 用 Spring 依赖注入替代动态 Loader |
| 插件清单 | `pluginInventory/list`（Loader 条目） | 返回 40 个已装配模块 | Java 无 Loader，以模块列表替代 |
| Agent 预设 | 按会话重组 agent 组合 | 全局 setSystemPrompt | 单 agent 架构，select 为全局切换提示 |
| 动态包 | `dynamicCordisRunner`（node:vm 沙箱） | 不支持 | Java 无 JS 沙箱，返回空清单 |
| Typert Remote | Typert 协议 + 生成编解码 | slash-path + JSON 直返 | Java 用 REST 路径替代 Typert 编解码 |
| 模型管理 | settings-driven provider profiles | ModelProfileStore + route 持久化 | Java 用 JSON 档案 + route 映射 |
| 会话流式 | 逐 token 流式（model.stream） | 非流式（model.chat）+ reasoning 捕获 | Java 用非流式 chat + reasoning_content |

> Web apiproxy 已支持 WebSocket 下行流（mux/host）、session/event 帧（surfaceOp:'append'）、tool-result content block（type:'tool-result'）、session/projection 帧（title 投影）、reasoning-delta chunk（思考内容）。
