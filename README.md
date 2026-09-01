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
├── dsh-agent/                   # Agent 接口、ReAct loop（maxParallelToolCalls 并行限流）、turn/step/round 生命周期、TurnObserver、setSystemPrompt
├── dsh-capability-shell/         # shell/bash 能力缝 + 本地提供者 + bash 工具（timeoutMs/maxOutputBytes 从 settings 读取）
├── dsh-capability-fs/            # 文件系统能力缝 + 本地提供者 + read/write/edit/glob/grep 工具
├── dsh-capability-web/           # Web 搜索/抓取能力缝 + DeepSeek 搜索 + HTTP 抓取 + web_search/web_fetch 工具（apiKeyEnv/baseURL/maxUses 从 settings 读取）
├── dsh-terminal/                 # 持久终端/PTY 能力缝 + bash 终端 + terminal 工具
├── dsh-compaction/               # 上下文压缩能力缝 + 摘要 provider + 工具结果裁剪器
├── dsh-subagent/                 # subagent 委派能力缝 + in-process fork + task 工具（per-delegation 模型选择 via allowedModels）
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
├── dsh-web/                     # Spring Boot Web 服务、apiproxy JSON-RPC 网关、SPA 托管、WebSocket 下行流、Session 导出
├── dsh-app/                     # 启动引导、Profile/Bundle 组合、Spring Boot 入口、RPC/ACP/CLI 入口、前端静态资源
├── frontend/                    # 原版 deepseek-harness Cordis 前端（原封复制，dist+启动快照已托管于 dsh-app/static）
├── scripts/                     # 构建 + 启动脚本（sh + bat 各一套）
└── testcase/                    # 端到端测试（TS + Python + sh + bat）
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
| dsh-agent | ReActAgentLoop（turn→step→round + maxParallelToolCalls 并行限流）+ TurnObserver + setSystemPrompt | 模板方法 + 状态机 + 策略 + 观察者 |
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
- **maxParallelToolCalls** —— 每 step 并行工具调用上限（settings `agent-loop` namespace，默认 10，harness 对齐）；`ReActAgentLoop.dispatchToolCalls` 按 cap 用 `ExecutorService` 并行派发，`executeToolCall` 接收 `TurnObserver` 参数（ThreadLocal 不传播到工作线程）。

不变式：**"模型可见 ⟺ 已记录"** —— 所有发送给模型的消息都从 `SessionLog` 投影得到。

## 快速开始

### 环境要求
- JDK 21
- Maven 3.9+
- Node.js 22+ / pnpm（仅前端构建时需要，运行时无需）

### 构建后端
```bash
scripts/build-backend.sh        # Linux/macOS
scripts\build-backend.bat       # Windows
```
构建产物：`dsh-app/target/classes/` + `dsh-app/target/rpc-cp.txt`（运行时 classpath）。

### 构建前端（可选，已预构建提交）
```bash
scripts/build-frontend.sh       # Linux/macOS（需 Node 22.19+/pnpm）
scripts\build-frontend.bat      # Windows
```
前端静态资源已构建并提交于 `dsh-app/src/main/resources/static`，无需运行时重建。

### 启动
```bash
scripts/start.sh [port]        # 打开 http://localhost:8765
```
启动脚本检查 `rpc-cp.txt` 是否存在（不存在则提示先运行 `build-backend`），然后直接启动 Spring Boot Web 服务。不再在启动时编译——编译由 `build-backend` 负责。

> Windows 每个脚本均有同名 `.bat`，与 `.sh` 一一对应。所有 `.bat` 为纯 ASCII 英文（编码无关）。

### 配置（模型 Key）

模型配置**完全来自网页保存**，不再从环境变量读取。后端启动时从 `~/.dsh/model-config.json` 加载活跃模型档案（含 API Key、端点、模型名）。

打开 http://localhost:8765 → 设置 → 模型：
- 添加自定义模型档案（显示名 / 模型名 / API Key / 端点）
- 切换当前活跃模型（即时生效，广播 `modelSelection` 投影，无需刷新）
- 删除模型档案
- 模型清单持久化到 `~/.dsh/model-config.json`（每个档案含 route + models 数组，跨重启存活）

## Web 设置页功能

### 模型管理（设置 → 模型）
- **添加/编辑/删除模型**：支持自定义 OpenAI 兼容提供方（DeepSeek、GLM、Qwen、OpenAI 等）
- **即时切换**：活跃模型切换后广播 `modelSelection` 投影帧，前端实时反映（无需刷新页面）
- **持久化**：模型档案 + 路由 + models 数组持久化到 `~/.dsh/model-config.json`
- **/model 切换**：聊天页 /model 弹窗 + composer 模型 seat 均调 `session.selectModel`，后端广播投影
- **schema 信封**：返回 schemastery 兼容的 `llm-pi-ai` namespace schema

### Agent 预设（设置 → Agent 预设）
- **四个系统预设**：standard（标准）、ptc（PTC 模式）、minimal（极简）、cordis（创造模式）
- **切换预设**：select 后全局切换 agent 系统提示（`Agent.setSystemPrompt`），下一回合生效
- **返回 id 字符串**：`agentPresetSelect` 返回 preset id 字符串（与原版 `Promise<string>` 一致），非对象（否则前端 React 渲染对象报错 → chip 消失）
- **基线一致性**：`buildControlBaseline` 的 `agentPreset` 用 `defaultPreset`（非硬编码 `"standard"`），与 `buildFollowSnapshot`/`sessionCreate` 一致

### 插件配置卡片（设置 → Plugins）
复刻 harness 四个插件配置卡片，每个 namespace 带 schema + 默认值（harness 对齐）：

| 卡片 | namespace | 字段 | 默认值 | 行为接入 |
|------|-----------|------|--------|---------|
| agent 循环 | `agent-loop` | `maxParallelToolCalls: number` | 10 | → ReActAgentLoop 并行限流 |
| 终端 | `shell` | `timeoutMs: number, maxOutputBytes: number` | 120000, 64000 | → BashTool 超时/输出截断 |
| 网页搜索 | `web-search-deepseek` | `apiKeyEnv: string, baseURL: string, maxUses: number` | -, -, 5 | → DeepSeekSearchProvider/WebSearchTool |
| subagent | `subagent-model-selection` | `enabled: boolean, allowedModels: array<{id,name}>` | false, [] | → SubagentTaskTool model 枚举 + per-delegation 子 agent |

### 欢迎声明（onboarding）
- `ui-onboarding` namespace 返回 + `welcomeNoticeVersion` 持久化到 `~/.dsh/settings.json`

### 打开配置文件
- `settings.openDocument` → `{opened:true}` + best-effort `Desktop.open(~/.dsh/model-config.json)`

## 认证（浏览器会话 Cookie）

后端 `/api/**` 和 `/ws/**` 需要浏览器会话 cookie 认证：
1. 后端启动时生成随机启动令牌，打印 `dsh web authentication URL: http://localhost:8765/?token=<token>`
2. 浏览器访问该 URL → 303 换发 `dsh-auth-<sha256(authority)>` cookie（HMAC-SHA256 签名，持久化到 `~/.dsh/browser-session.json`）
3. 后续所有 `/api` + `/ws` 请求凭 cookie 认证

E2E 测试脚本（`web-e2e.sh` / `run-all.sh`）自动从后端日志解析 token → `GET /?token=` 换 cookie → `-b cookie.jar` 带在所有 `/api` 调用上。

## 会话与工作区

### 会话标题
- **自动生成**：从原始事件流过滤 `source.kind=plugin` 的上下文注入消息，取首条真实用户消息前 40 字符
- **投影推送**：`session.prompt` 时推送 `session/projection` control 帧（key=title, seq=lastSeq）
- **分叉标题**：`buildFollowSnapshot` 复用 `generateTitle(sl)`（与 `buildControlBaseline`/`sessionFork` 同款，跳过 plugin 注入消息），live 与刷新一致
- **手动重命名**：`session.rename` 存储标题 + 推送投影

### 工作区
- **自动分组**：未分组会话发送消息时自动按 `yyyy-MM-dd-HH` 创建/复用时段工作区
- **持久化**：工作区 + 归档集持久化到 `~/.dsh/workspaces.json`
- **管理操作**：create / rename / delete / archiveSession / insertBefore / insertSessionBefore / list

### 会话操作
- **session.create** → 返回 sessionId
- **session.prompt** → 异步运行 agent turn（ReAct 循环，含工具调用）
- **session.history** → 分页返回事件 + projections 块（title 投影）
- **session.fork** → 复制父会话全部事件 + 注入 forked-from 上下文消息，推送 title 投影（与刷新一致）
- **session.cancel** → 中断运行中的 agent turn 线程
- **session.rename** → 更新会话标题
- **session.selectModel** → 切换活跃模型 + 广播 `modelSelection` 投影（实时，无需刷新）
- **session.list** → 返回所有会话（含 title / blank / running / updatedAt）
- **session.export** → `GET /api/session.export?sessionId=…` → ZIP 下载（.jsonl）

### 事件信封（apiproxy → 前端）
- `request/header` 带 `reason:'initial'`（与 harness 对齐，使轨迹渲染系统提示词）
- `surfaceOp:'append'` 标记在 `user/message`、`assistant/message`、`tool/result`
- `user/message` 的 `source.rpcId` 与前端 `beginSubmission` 的 `requestId` 对齐（echo retire 去重，避免发送内容显示两次）
- `assistant/chunk` 支持 `reasoning-delta`（思考内容）+ `text-delta`（回复内容）
- `turn/end` reason: `complete` 或 `aborted`（取消时）

## 启动方式

| 脚本 | 入口类 | 传输/模式 | 说明 |
|------|--------|-----------|------|
| `start.sh` | `DshApplication` | Web（SPA+REST+SSE+WS） | 启动 Web 服务（推荐） |
| `start-rpc.sh` | `DshRpcServer` | stdio JSON-RPC | 运行时 SDK 全功能（进程外子进程） |
| `start-acp.sh` | `DshAcpServer` | stdio JSON-RPC | ACP 自动化最小方法集 |
| `start-cli.sh` | `DshRepl` | 终端 REPL | 交互式对话（斜杠命令） |

> 启动脚本不再编译——先运行 `build-backend` 生成 `target/classes` + `rpc-cp.txt`，再启动。
> 模型/key/端点取自 `~/.dsh/model-config.json`（网页保存的活跃档案），不从环境变量读取。

### 下行流（WebSocket）
- `/api/events.mux` → mux WS（session/event + session/projection + session/subscribed 帧）
- `/api/events.host` → host WS（host/session-added / session-status / workspace-changed / archived-sessions-changed / remote-event 帧）
- mux WS 连接时自动推送所有会话的 title 投影

### CLI 输出
- 推理块显示 `---think---` ... `-----------`（非 `-- think --`，无 ✓ 勾）
- 工具结果：换行 + 工具名（DIM），不再显示 `✓` 勾

### CLI 斜杠命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/model [id]` | 列出/切换模型 |
| `/sessions` | 列出全部会话（含持久化） |
| `/session <id>` | 按前缀切换会话 |
| `/fork` | 分叉当前会话 |
| `/compact` | 压缩上下文 |
| `/new` | 新建会话 |
| `/tokens` | 查看 token 用量 |
| `/exit` | 退出 |

## TurnOrchestrator（共享 turn 逻辑）

Web/CLI/RPC/ACP 四种入口共享 `TurnOrchestrator`（`dsh-web` 模块）：

- **`prepareTurn`** — turn 0 时注入 `request/header`（含 `reason:'initial'` + 系统提示词 `{{cwd}}`/`{{model}}`/`{{platform}}`）+ `request/context` + 上下文消息（AGENTS.md / runtime context / skills），后续轮不重复注入
- **`runAgent`** — `agent.runObserved` + TurnObserver → 事件日志（step/start/end、assistant/chunk/message、tool/call/result、turn/end）
- **`forkSession`** — 复制父事件 + 注入 forked-from `user/message`
- **`generateTitle`** — 从原始事件过滤 `source.kind=plugin`，取首条真实用户消息前 40 字符

### 系统提示词模板

| 变量 | 解析 |
|------|------|
| `{{cwd}}` | 会话工作区路径（`SessionCwd`，虚拟线程 ThreadLocal） |
| `{{model}}` | 当前活跃模型名 |
| `{{platform}}` | OS 名 + 架构 + shell 类型 |

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

> 所有 `/api/**` 和 `/ws/**` 需要浏览器会话 cookie 认证（见「认证」节）。

## 端到端测试

### 运行
```bash
# 先构建后端
scripts/build-backend.sh

# 运行全部 e2e（启动后端 + 认证握手 + 依次跑 5 项）
bash testcase/run-all.sh

# 单独运行
bash testcase/web-e2e.sh [port]       # HTTP/SSE 契约（health/send/stream）
python3 testcase/ws-e2e.py            # WebSocket（并发多 session + 流式 + 取消）
mvn -o -pl testcase exec:java -Dexec.mainClass=com.deepseek.dsh.testcase.RpcE2e  # RPC stdio
node testcase/e2e.ts                  # TS 协议验证（需 DSH_TOKEN）
python3 testcase/frontend-e2e.py      # SPA chromium 交互（需 playwright + chromium）
```

### 测试覆盖

| 脚本 | 验证内容 | 测试数 |
|------|---------|--------|
| `web-e2e.sh` | health + send（完整回复）+ stream（SSE delta） | 3 |
| `ws-e2e.py` | 并发多 session + 流式 + 会话取消 | 2 |
| `RpcE2e.java` | 基础问候 / 完整返回 / 记忆保存 / fork / 列表 / 压缩 / 删除 / 技能 / subagent 委派 | 12 |
| `e2e.ts` | 认证 / session.create / prompt / history / fork / compact / WebSocket / 模型切换 / 工具调用 / packed history | 14 |
| `frontend-e2e.py` | SPA 渲染 → 输入 → 发送 → 回复渲染（chromium） | 1 |

> 认证握手：所有 e2e 脚本自动从后端日志解析启动令牌 → `GET /?token=` 换 cookie → 后续调用带 `-b cookie.jar`。

## 单元测试

```bash
mvn test                          # 运行全部单元测试（全模块，离线）
```

230+ 个单元测试覆盖全部后端模块的核心纯逻辑（无网络、无外部依赖）。

## 数据文件

| 文件 | 说明 |
|------|------|
| `~/.dsh/model-config.json` | 模型档案（id/displayName/apiKey/baseUrl/model/route/models） |
| `~/.dsh/settings.json` | 用户设置（ui-onboarding/agent-presets/shell/agent-loop/web-search-deepseek/subagent-model-selection 等命名空间） |
| `~/.dsh/workspaces.json` | 工作区注册表（workspaces + archived sessions） |
| `~/.dsh/presets/*.yml` | 用户自定义 agent 预设 |
| `~/.dsh/sessions/*.jsonl` | 会话事件日志（事件溯源，JSONL 追加） |
| `~/.dsh/browser-session.json` | 浏览器认证签名密钥 |

> 所有数据文件已 gitignore，绝不提交 API Key。

## 会话持久化

- `SessionStore.listAll()` — 扫描持久化目录（JSONL 文件 / SQLite `SELECT DISTINCT`）
- `SessionManager.list()` — 合并内存活跃 + 持久化会话，跨重启存活
- `getOrCreate(id)` — 自动从持久化重放历史
- `sessionList` / `buildControlBaseline` — 用 `getOrCreate` 加载，`cwd` 取工作区路径，`updatedAt` 取最后事件时间，`running` 取 `runningTurns` 实时状态

## 与原项目的关系

已实现（41 个模块，230+ Java 源文件，230+ 个单元测试全绿）：

**核心层**：插件基座（Context/Plugin + 可逆副作用 + 作用域遮蔽 + Event Bus 四模式 + Middleware 链）、
agent loop（ReAct + turn/step/round + maxParallelToolCalls 并行限流 + TurnObserver 事件映射 + setSystemPrompt 预设切换）、
事件溯源会话日志、工具注册表与执行管线、DeepSeek LLM 适配器（流式 + 重试 + Token 计量 + reasoning_content 捕获 + 模型配置中心）。

**能力层**：bash/shell（timeoutMs/maxOutputBytes 从 settings 读取）、文件系统（read/write/edit/glob/grep）、持久终端/PTY、
代码运行时（Python）、LSP、Web 搜索/抓取（apiKeyEnv/baseURL/maxUses 从 settings 读取）、沙箱、托管子进程组。

**交互层**：审批、权限预设、斜杠命令、ask-user、计划模式、目标、后台任务、任务清单、
重复调用提醒 + 超时策略、AGENTS.md + 时间注入 + @file 引用解析。

**数据/存储层**：凭据/授权、用户设置（JSON 命名空间，~/.dsh/settings.json 持久化）、
通用存储中心、外溢存储、技能目录/加载器、SQLite + FTS5、上下文压缩、
内容寻址附件、工作区注册表（~/.dsh/workspaces.json 持久化 + 归档 + 自动时段分组）。

**集成层**：subagent 委派（in-process fork + task 工具 + per-delegation 模型选择 via allowedModels）、工作流引擎、Agent Teams、
遥测、MCP 客户端、ACP 服务器、SDK。

**Web 层**：Spring Boot + apiproxy JSON-RPC 网关、
WebSocket mux/host 下行流（session/event + session/projection + host/* 帧）、
SPA 托管、Session 导出 ZIP、WorkspaceRegistry 持久化、
浏览器会话 cookie 认证（token→cookie 握手）。

**前端**：React + Vite，保留原 `--dsw-*` 设计令牌的深色对话式风格，对接 Java 后端 apiproxy。

### 架构差异说明

Java 重写版与原版 TS Harness 的主要架构差异：

| 领域 | TS 原版 | Java 重写 | 说明 |
|------|---------|-----------|------|
| 插件加载 | Cordis Loader（动态 cordis.yml 组合） | Spring DI + 静态 BaseBundle | Java 用 Spring 依赖注入替代动态 Loader |
| Agent 预设 | 按会话重组 agent 组合 | 全局 setSystemPrompt | 单 agent 架构，select 为全局切换提示 |
| 模型管理 | settings-driven provider profiles | ModelProfileStore + route 持久化 | Java 用 JSON 档案 + route 映射 |
| 工具结果 | SessionEvent.Payload | Map<String,Object> wire 格式 | deriveMessages 递归提取 tool-result 嵌套 content |
| Shell | bash + pwsh 同时注册 | 平台互斥（Unix→bash, Windows→pwsh） | 模型只看到当前平台可用的 shell |
| Turn 逻辑 | 各入口独立 | TurnOrchestrator 共享 | Web/CLI/RPC/ACP 统一注入+事件+错误处理 |
| 认证 | TS 原版认证 | BrowserAuthFilter（token→cookie 握手） | Java 实现等价的浏览器会话认证 |
| 并行工具 | agent-loop maxParallelToolCalls | ReActAgentLoop.dispatchToolCalls | 按 settings cap 用 ExecutorService 并行 |
| 插件配置 | Cordis plugin cards | settings.describe 四 namespace | agent-loop/shell/web-search-deepseek/subagent-model-selection |
