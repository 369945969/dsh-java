# AgentScope 独立工程

基于阿里 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)（`io.agentscope:agentscope-harness:2.0.1`）的独立 Web/WS 智能体服务。不依赖 dsh-java 任何包。

## 快速开始

```bash
# 1. 编译
cd agentscope
bash scripts/build.sh

# 2. 设置环境变量
export DSH_TOKEN="your-token"
export DASHSCOPE_API_KEY="sk-7c1ead..."

# 3. 启动（WS 模式 = HTTP + WebSocket 流式）
bash scripts/start-ws.sh 8766

# 4. 打开浏览器
# http://localhost:8766/?token=your-token

# 5. 运行测试
DSH_TOKEN=your-token DSH_PORT=8766 node testcase/e2e.ts
```

## 能力

| 能力 | 实现 |
|---|---|
| Web HTTP REST | JDK HttpServer（/api/agent/send, /api/agent/health） |
| WebSocket 流式 | java-websocket（/ws/agent, prompt→delta→done, cancel） |
| Session | agentscope 原生 RuntimeContext + AgentStateStore（自动持久化/恢复） |
| 系统提示词 | HarnessAgent.sysPrompt() |
| 用户 Skill | workspace/skills/*.md（agentscope 自动加载） |
| MCP | McpClientBuilder.stdio/streamableHttp/sse（扩展点） |
| Token 鉴权 | env DSH_TOKEN, ?token=→cookie |
| LLM | dashscope:qwen3.7-max（DashScope 扩展） |
| 前端 UI | HTML+JS（token 验证页 + 左对话列表 + 右聊天框，仿 DeepSeek） |

## 文档

- [DESIGN.md](DESIGN.md) — 设计文档
- [DEPLOY.md](DEPLOY.md) — 部署文档
