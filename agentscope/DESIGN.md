# AgentScope 独立工程设计

基于阿里 **AgentScope Java**（`io.agentscope:agentscope-harness`）构建独立智能体服务，提供 Web + WebSocket 访问能力。不依赖 dsh-java 任何包，全部以 agentscope 原生能力为核心。

## 1. 目标

- Web（HTTP REST + 静态 HTML/JS 交互界面）+ WebSocket 双通道访问 agent
- session 能力（多会话隔离）
- 系统提示词注入
- 用户 skill（从 skills/ 目录加载，agentscope Toolkit 原生支持）
- MCP 连接（agentscope Toolkit 原生支持 MCP server）
- token 鉴权（参考 dsh 的 ?token= 握手 + cookie）
- LLM：qwen3.7-max via DashScope（复用 sk-7c1ead… key）
- 不接数据库（session 内存态；agentscope 原生可选 MySQL/Redis，本期不开）

## 2. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| Agent 引擎 | `io.agentscope:agentscope-harness:2.0.1` | HarnessAgent（ReAct + skill/记忆/subagent + 事件流） |
| 模型 | `io.agentscope:agentscope-extensions-model-dashscope:2.0.1` | `dashscope:qwen3.7-max`，读 `DASHSCOPE_API_KEY` env |
| HTTP | JDK 内置 `com.sun.net.httpserver.HttpServer` | 零额外依赖，托管静态 UI + REST |
| WebSocket | `org.java-websocket:Java-WebSocket` | 轻量 WS server，转发 agentscope 事件流 |
| JSON | Jackson（agentscope 传递依赖） | 请求/响应/事件帧 |
| 构建 | Maven（独立 pom，非 dsh-java 子模块） | `scripts/build.sh` |
| 运行 | JDK 17+ | `scripts/start-web.sh` / `start-ws.sh` |

## 3. 目录结构

```
agentscope/
├── pom.xml                      # 独立 maven 工程，依赖 io.agentscope:*
├── DESIGN.md                    # 本设计文档
├── DEPLOY.md                    # 部署文档
├── README.md
├── scripts/
│   ├── build.sh                 # mvn clean package
│   ├── start-web.sh             # 启动 web 模式（HTTP + 静态 UI）
│   └── start-ws.sh              # 启动 ws 模式（HTTP + WebSocket）
├── src/main/java/com/agentscope/
│   ├── AgentscopeApp.java       # main：装配 HarnessAgent + 启 WebServer/WsServer
│   ├── config/Config.java       # token/port/model/skillsDir/mcp 配置（env）
│   ├── agent/AgentFactory.java  # 构建 HarnessAgent（sysPrompt/model/workspace/Toolkit[tools+skills+mcp]）
│   ├── session/SessionManager.java  # sessionId→RuntimeContext+历史，内存态
│   ├── web/WebServer.java       # JDK HttpServer：静态 + POST /api/agent/send + GET /api/agent/health + token
│   ├── ws/WsServer.java         # java-websocket /ws/agent：prompt(streamEvents 转发) + cancel + token
│   └── auth/TokenFilter.java    # token 校验（?token= 或 X-DSH-Token header → cookie）
├── src/main/resources/
│   ├── static/
│   │   ├── index.html           # 交互界面（聊天框 + session 选择 + 流式渲染）
│   │   └── app.js               # SSE/fetch 流式 + WS 流式
│   └── skills/
│       └── code-review.md       # 示例 skill（agentscope skill 格式）
└── testcase/
    └── run-e2e.sh               # web + ws 端到端（带 token）
```

## 4. 核心 API 对接（AgentScope Java 2.0）

来自 agentscope-java README（`io.agentscope`）：

```java
// 装配 agent（原生 HarnessAgent）
HarnessAgent agent = HarnessAgent.builder()
    .name("agentscope-bot")
    .sysPrompt(systemPrompt)                 // 系统提示词注入
    .model("dashscope:qwen3.7-max")          // ModelRegistry 解析 + 读 DASHSCOPE_API_KEY
    .workspace(Paths.get(".agentscope/ws"))   // skill 仓库 / 记忆 / subagent
    .toolkit(Toolkit.builder()
        .tools(Bash, Read, Write, Edit, Grep, Glob)   // 内置 coding 工具
        .skills(skillsDir)                            // 用户 skill（Markdown）
        .mcpServers(mcpConfig)                        // MCP 连接
        .build())
    .build();

// 运行上下文（session/user 隔离）
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId(sid).userId(uid).build();

// 阻塞调用（web /api/agent/send 用）
AgentResult result = agent.call(new UserMessage(msg), ctx).block();

// 流式事件（ws /ws/agent 用）——31 类事件，转发给前端
agent.streamEvents(new UserMessage(msg), ctx)
    .doOnNext(ev -> ws.send(toFrame(ev)))     // TextBlockDelta/ToolCallStart/...
    .blockLast();
```

> 注：Toolkit 的 skills/mcp 构建器签名以 agentscope-java 实际 API 为准（编译期对齐，必要时 fetch 文档校准）。

## 5. Web 协议（HTTP REST）

| 方法 路径 | 作用 | 鉴权 |
|---|---|---|
| GET `/?token=xxx` | 换 cookie（同 dsh 握手） | token |
| GET `/api/agent/health` | 健康检查 | cookie |
| POST `/api/agent/send` | 一次性对话（body: `{sessionId?,message}` → `{sessionId,reply,tokens}`） | cookie |
| GET `/` | 静态 index.html | — |

## 6. WebSocket 协议（/ws/agent）

握手带 cookie（同源）。JSON 文本帧：

- C→S: `{"action":"prompt","sessionId":"s1","message":"..."}`
- C→S: `{"action":"cancel","sessionId":"s1"}`
- S→C: `{"event":"session|text|tool_call|tool_result|done|cancelled|error","sessionId":"s1","data":"..."}`
  - `text` = TextBlockDelta 累积
  - `tool_call`/`tool_result` = 工具调用/结果事件
  - `done` = 回合结束

## 7. Session 能力

- `SessionManager`：`ConcurrentHashMap<sessionId, SessionState>`，SessionState 持 RuntimeContext + 历史摘要。
- `sessionId` 由客户端传或缺省生成；`userId` 从 header `X-DSH-USERID` 取（缺省 `default`）。
- 内存态（重启丢）；agentscope 原生 `AgentStateStore` 可选 MySQL/Redis 扩展（本期不开）。

## 8. Skill / MCP

- Skill：`src/main/resources/skills/*.md`（agentscope skill 格式：frontmatter + 正文），`Toolkit.skills(dir)` 加载，agent 可发现并调用。
- MCP：`Toolkit.mcpServers(config)` 连接 stdio/SSE MCP server，其工具并入 agent Toolkit。配置走 env `DSH_MCP_SERVERS`（JSON，可选）。

## 9. Token 鉴权

- `DSH_TOKEN` env（默认固定值，便于测试）。
- web：`GET /?token=xxx` → set-cookie `dsh-auth=…`；后续 `/api/*` 校验 cookie。
- ws：握手带 cookie（`Cookie` header）。

## 10. 脚本

- `build.sh`：`mvn -q clean package`，生成 `target/agentscope-*.jar` + classpath。
- `start-web.sh [port]`：启动 HTTP（REST + 静态 UI），`java -cp … AgentscopeApp web $PORT`。
- `start-ws.sh [port]`：启动 HTTP + WebSocket，`java -cp … AgentscopeApp ws $PORT`。

## 11. 测试（testcase，TypeScript）

> 用 TS 写，前端 `app.js`/`app.ts` 与测试共享同一套 client 库（HTTP/SSE + WS 调用逻辑复用）。

`testcase/` 结构：
```
testcase/
├── client.ts          # 共享 client：httpSend/httpHealth/wsPrompt（前端 + 测试复用）
├── e2e.ts             # 端到端用例（node 直跑，Node 22+ strip-types）
├── run-e2e.sh         # 包壳：确保服务起 + node testcase/e2e.ts
├── package.json       # 无运行时依赖（仅 Node 内置 fetch + WebSocket）
└── tsconfig.json
```
覆盖（带 token）：
1. `?token=` 握手换 cookie
2. `/api/agent/health` → ok
3. `/api/agent/send` → reply 非空 + token > 0
4. 多轮记忆（同 sessionId 记住→回忆）
5. skill.list（agent 发现 code-review skill）
6. WS：`prompt` → 收到 session/text/done 帧
7. WS：`cancel` → cancelled
8. 无 token → 401

跑法：`bash testcase/run-e2e.sh`（确保 web/ws 服务就绪 + `node testcase/e2e.ts`）。

## 12. 交付

- `DESIGN.md`（本文件）/ `DEPLOY.md`（部署）/ `README.md`
- 可运行工程（build + start-web/start-ws）
- e2e 通过
