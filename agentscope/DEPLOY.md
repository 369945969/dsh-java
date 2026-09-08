# AgentScope 独立工程部署文档

## 1. 概述

基于阿里 AgentScope Java（`io.agentscope:agentscope-harness:2.0.1`）的独立智能体服务，提供 Web HTTP + WebSocket 双通道访问。不依赖 dsh-java 任何包。

### 能力清单
- ✅ Web HTTP REST + 静态 HTML/JS 交互界面（仿 DeepSeek 聊天布局）
- ✅ WebSocket 流式（session→delta→done）
- ✅ session 隔离（agentscope 原生 AgentStateStore，JsonFile 自动持久化到 `~/.agentscope/state/`）
- ✅ 系统提示词注入（`.sysPrompt(...)`）
- ✅ 用户 skill（`workspace/skills/*.md`，agentscope 原生自动加载）
- ✅ MCP 连接（`McpClientBuilder.stdio/streamableHttp/sse`）
- ✅ token 鉴权（环境变量 `DSH_TOKEN`，Web `?token=` 握手换 cookie）
- ✅ LLM：qwen3.7-max via DashScope

## 2. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | AgentScope Java 最低要求 |
| Maven | 3.9+ | 构建 |
| Node.js | 22+ | 运行 TS testcase |
| DashScope API Key | — | `DASHSCOPE_API_KEY` 环境变量 |

## 3. 构建与启动

### 3.1 编译

```bash
cd agentscope
bash scripts/build.sh   # mvn clean package → target/agentscope-1.0.0.jar
```

### 3.2 启动 Web 模式（HTTP + 静态 UI，无 WS）

```bash
export DSH_TOKEN="my-secret-token"
export DASHSCOPE_API_KEY="sk-7c1ead..."
bash scripts/start-web.sh 8766
# 打开 http://localhost:8766/?token=my-secret-token
```

### 3.3 启动 WS 模式（HTTP + WebSocket 流式）

```bash
export DSH_TOKEN="my-secret-token"
export DASHSCOPE_API_KEY="sk-7c1ead..."
bash scripts/start-ws.sh 8766
# Web: http://localhost:8766/?token=...
# WS:  ws://localhost:8767/ws/agent
```

### 3.4 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `DSH_TOKEN` | `agentscope-default-token` | 访问令牌（系统变量） |
| `DASHSCOPE_API_KEY` | — | DashScope API Key（必填） |
| `DSH_MODEL` | `dashscope:qwen3.7-max` | 模型 id（ModelRegistry 解析） |
| `DSH_PORT` | `8766` | HTTP 端口（WS = HTTP + 1） |
| `DSH_WORKSPACE` | `.agentscope/workspace` | 工作区目录（skill/记忆/session） |
| `DSH_SYS_PROMPT` | 默认提示词 | 系统提示词注入 |
| `DSH_SKILLS_DIR` | `src/main/resources/skills` | 技能目录 |

## 4. 架构

```
┌─────────────────────────────────────────────┐
│              AgentscopeApp (main)            │
│  ┌───────────────────────────────────────┐  │
│  │  HarnessAgent (agentscope 原生)        │  │
│  │  .sysPrompt / .model(dashscope:...)    │  │
│  │  .workspace (skills/memory/session)    │  │
│  │  .call() / .streamEvents()             │  │
│  └───────────────────────────────────────┘  │
│  ┌─────────────┐  ┌──────────────────────┐ │
│  │ WebServer    │  │ WsServer (可选)       │ │
│  │ JDK HttpServer│  │ java-websocket       │ │
│  │ /api/send    │  │ /ws/agent            │ │
│  │ /api/health  │  │ prompt→streamEvents   │ │
│  │ 静态 UI      │  │ cancel                │ │
│  └─────────────┘  └──────────────────────┘ │
│          token 鉴权（DSH_TOKEN env）         │
└─────────────────────────────────────────────┘
```

## 5. 前端界面

- **Token 验证页**：输入 token 或 URL 带 `?token=` 自动进入
- **布局**：左侧对话列表 + 右侧聊天框 + 输入框（仿 DeepSeek 聊天界面）
- **对话列表**：localStorage 持久化，支持新建/切换/删除
- **流式渲染**：WS 模式下 delta 帧实时追加

## 6. 测试

```bash
# 确保 web/ws 服务已启动
export DSH_TOKEN="my-secret-token"
bash testcase/run-e2e.sh
# 或直接：DSH_TOKEN=xxx node testcase/e2e.ts
```

覆盖：health / send / 多轮记忆 / 无 token 401 / WS prompt（session→delta→done）

## 7. MCP 扩展

在 `AgentscopeApp.java` 中添加 MCP client（需额外 MCP SDK 依赖）：

```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(
    McpClientBuilder.stdio().name("filesystem")
        .command("npx").args("-y", "@anthropic/mcp-filesystem").build()
).block();

HarnessAgent agent = HarnessAgent.builder()
    .name("agentscope-bot").sysPrompt(...).model(...)
    .workspace(...).toolkit(toolkit).build();
```

## 8. Session 持久化

agentscope 原生 `JsonFileAgentStateStore` 自动持久化到 `~/.agentscope/state/<agentId>/<userId>/<sessionId>/agent_state.json`。重启后同 `(userId, sessionId)` 自动恢复对话。生产环境可换 `RedisAgentStateStore` 或 `MySQLAgentStateStore`。
