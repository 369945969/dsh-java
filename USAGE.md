# DSH-Java 使用指南

> 面向使用者和二次开发者的完整操作手册。
> 涵盖：安装、配置、启动、Web/CLI/RPC/ACP 四种模式、端到端测试、二次开发。

---

## 目录

1. [环境要求](#1-环境要求)
2. [快速开始](#2-快速开始)
3. [模型配置](#3-模型配置)
4. [四种启动模式](#4-四种启动模式)
5. [Web 聊天功能](#5-web-聊天功能)
6. [设置页功能](#6-设置页功能)
7. [CLI 交互模式](#7-cli-交互模式)
8. [端到端测试](#8-端到端测试)
9. [认证机制](#9-认证机制)
10. [数据文件](#10-数据文件)
11. [REST / SSE API](#11-rest--sse-api)
12. [二次开发](#12-二次开发)

---

## 1. 环境要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 21+ | 后端运行时 |
| Maven | 3.9+ | 后端编译 |
| Node.js | 22.19+ / 24+ | 前端构建（运行时不需要） |
| pnpm | 最新 | 前端依赖管理（构建时） |
| curl | 任意 | e2e 测试 |
| Python 3 | 3.10+ | ws-e2e.py / frontend-e2e.py |
| websockets (pip) | 最新 | ws-e2e.py |
| playwright (pip) | 最新 | frontend-e2e.py（可选） |
| chromium | 任意 | frontend-e2e.py（可选） |

### Windows 额外依赖
- PowerShell 7 (`pwsh`) 或 Windows PowerShell 5.1（`scripts\start.bat` 会自动检测）
- `curl`（Win10 1803+ 自带）
- `findstr`（系统自带）

---

## 2. 快速开始

### Linux / macOS

```bash
# 1. 构建后端
scripts/build-backend.sh

# 2. 启动 Web 服务（默认端口 8765）
scripts/start.sh

# 3. 打开浏览器
open http://localhost:8765
```

### Windows (cmd / PowerShell)

```bat
:: 1. 构建后端
scripts\build-backend.bat

:: 2. 启动 Web 服务
scripts\start.bat

:: 3. 打开浏览器（启动时会打印 token URL）
start http://localhost:8765
```

> **注意**：启动脚本不再在启动时编译。请先运行 `build-backend` 生成 `dsh-app/target/classes/` + `dsh-app/target/rpc-cp.txt`，再运行 `start`。

### 构建产物说明

| 产物 | 路径 | 说明 |
|------|------|------|
| 编译类 | `dsh-app/target/classes/` | Java 字节码 |
| 运行时 classpath | `dsh-app/target/rpc-cp.txt` | 所有依赖 jar 路径 |
| 前端静态资源 | `dsh-app/src/main/resources/static/` | 已预构建提交，运行时直接托管 |

### 前端重建（可选）

前端已预构建并提交，通常无需重建。如需修改前端：

```bash
# Linux / macOS
scripts/build-frontend.sh

# Windows
scripts\build-frontend.bat
```

---

## 3. 模型配置

模型配置**完全来自网页保存**，不再从环境变量（`.env`）读取。

### 首次配置

1. 启动后端（`start.sh` / `start.bat`）
2. 打开 `http://localhost:8765/?token=<启动日志中的 token>`
3. 进入 **设置 → 模型**
4. 点击「添加自定义模型」，填写：
   - 显示名（如 `GLM-5.2`）
   - 模型名（如 `glm-5.2`）
   - API Key
   - 端点（如 `https://dashscope.aliyuncs.com/compatible-mode/v1`）
5. 保存 → 自动设为活跃模型 → 下一回合生效

### /model 切换

在聊天页输入 `/model` 或点击 composer 的模型标签 → 弹出模型选择器 → 选择模型 → 即时生效（广播 `modelSelection` 投影，无需刷新）。

### 模型清单持久化

模型配置保存到 `~/.dsh/model-config.json`：
```json
{
  "activeId": "xxx",
  "profiles": [
    {
      "id": "xxx",
      "displayName": "GLM-5.2",
      "apiKey": "sk-...",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "model": "glm-5.2",
      "models": [{"id": "glm-5.2", "name": "glm-5.2"}]
    }
  ]
}
```

> 跨重启自动加载。每个档案可配多个 `models`（出现在 `/model` 选择器里）。API Key 绝不提交（`.gitignore`）。

---

## 4. 四种启动模式

| 脚本 | 入口类 | 传输/模式 | 用途 |
|------|--------|-----------|------|
| `start.sh` / `start.bat` | `DshApplication` | Web（SPA+REST+SSE+WS） | 一键启动 Web 服务（推荐） |
| `start-cli.sh` / `start-cli.bat` | `DshRepl` | 终端 REPL | 交互式命令行对话 |
| `start-rpc.sh` / `start-rpc.bat` | `DshRpcServer` | stdio JSON-RPC | SDK 进程外运行时 |
| `start-acp.sh` / `start-acp.bat` | `DshAcpServer` | stdio JSON-RPC | ACP 自动化最小方法集 |

### 4.1 Web 模式

```bash
# Linux / macOS
scripts/start.sh [port]           # 默认 8765

# Windows
scripts\start.bat [port]
```

启动后终端打印：
```
dsh web authentication URL: http://localhost:8765/?token=<随机令牌>
```

浏览器打开该 URL → 自动换发 cookie → 进入聊天界面。

### 4.2 CLI 模式

```bash
# Linux / macOS
scripts/start-cli.sh

# Windows
scripts\start-cli.bat
```

进入交互式 REPL，支持斜杠命令（见 [CLI 交互模式](#7-cli-交互模式)）。

### 4.3 RPC 模式

```bash
# Linux / macOS
scripts/start-rpc.sh

# Windows
scripts\start-rpc.bat
```

通过 stdio 传输 JSON-RPC 2.0，供 SDK 客户端（`dsh-sdk`）进程外调用。stdout 仅承载 JSON-RPC 帧，所有日志走 stderr。

### 4.4 ACP 模式

```bash
# Linux / macOS
scripts/start-acp.sh

# Windows
scripts\start-acp.bat
```

ACP（Agent Client Protocol）自动化最小方法集：`session.create` / `session.run` / `session.list` / `shutdown`。

---

## 5. Web 聊天功能

### 基本操作
- 在输入框输入消息 → Enter 发送
- `Shift+Enter` 换行
- 输入 `/` 触发斜杠命令
- 输入 `@` 引用文件/会话

### 轨迹面板
- 点击 **Trajectory** 标签查看 agent 执行轨迹
- 轨迹按事件 seq 升序排列（与聊天对齐）
- 系统提示词显示在轨迹最上方（`request/header` + `reason:initial`）
- 工具调用在各自 step 内显示（`assistant/message` 带 `toolCalls`）
- 事件过多时显示「加载更多」按钮（follow 快照按 `maxMessages` 分页）

### 会话管理
- **新建会话**：点击「New Session」
- **分叉**：`/fork` 或会话菜单 → 复制全部事件到子会话（保留记忆）
- **重命名**：会话菜单 → 输入新标题
- **取消**：运行中可点击「停止生成」中断 agent turn
- **导出**：`GET /api/session.export?sessionId=...` → ZIP 下载

### 工作区
- 未分组会话发送消息时自动按 `yyyy-MM-dd-HH` 创建时段工作区
- 工作区跨重启持久化到 `~/.dsh/workspaces.json`

---

## 6. 设置页功能

### 模型管理（设置 → 模型）
- 添加 / 编辑 / 删除模型档案
- 即时切换活跃模型（广播 `modelSelection` 投影，无需刷新）
- `/model` 弹窗只显示已配置的模型（profile 的 `models` 数组）

### Agent 预设（设置 → Agent 预设）

| 预设 | 说明 |
|------|------|
| `standard` | 标准模式（功能完整） |
| `ptc` | PTC 模式（TypeScript 程序组合多步操作） |
| `minimal` | 极简模式（仅 bash + str_replace_editor） |
| `cordis` | 创造模式（运行时检查 + preset 创作） |

切换预设 → 全局切换 agent 系统提示 → 下一回合生效。

### 插件配置卡片（设置 → Plugins）

| 卡片 | namespace | 字段 | 默认值 | 行为 |
|------|-----------|------|--------|------|
| agent 循环 | `agent-loop` | `maxParallelToolCalls` | 10 | ReActAgentLoop 并行限流 |
| 终端 | `shell` | `timeoutMs` / `maxOutputBytes` | 120000 / 64000 | BashTool 超时/输出截断 |
| 网页搜索 | `web-search-deepseek` | `apiKeyEnv` / `baseURL` / `maxUses` | - / - / 5 | DeepSeekSearchProvider |
| subagent | `subagent-model-selection` | `enabled` / `allowedModels` | false / [] | SubagentTaskTool model 枚举 + per-delegation 子 agent |

---

## 7. CLI 交互模式

### 启动

```bash
scripts/start-cli.sh       # Linux / macOS
scripts\start-cli.bat      # Windows
```

### 斜杠命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/model [id]` | 列出/切换模型 |
| `/sessions` | 列出全部会话 |
| `/session <id>` | 按前缀切换会话 |
| `/fork` | 分叉当前会话 |
| `/compact` | 压缩上下文 |
| `/new` | 新建会话 |
| `/tokens` | 查看 token 用量 |
| `/exit` | 退出 |

### CLI 输出格式
- 推理块：`---think---` ... `-----------`（无 ✓ 勾）
- 工具结果：换行 + 工具名（DIM）
- 系统提示词注入：`[系统提示词已注入]`
- 上下文注入：`[上下文注入] ...`

---

## 8. 端到端测试

### 8.1 全量 e2e（一键）

```bash
# Linux / macOS
bash testcase/run-all.sh

# Windows
testcase\run-all.bat
```

`run-all` 依次执行：
1. `mvn install`（构建）
2. RpcE2e（stdio JSON-RPC，12+ 项）
3. 启动 Web 服务端 + 认证握手
4. `web-e2e.sh`（HTTP/SSE，3 项）
5. `ws-e2e.py`（WebSocket，2 项）
6. `frontend-e2e.py`（chromium SPA，可选）

### 8.2 单独运行

```bash
# Web HTTP/SSE 契约
bash testcase/web-e2e.sh [port]
# Windows
testcase\web-e2e.bat [port]

# WebSocket 并发 + 取消
python3 testcase/ws-e2e.py

# RPC stdio（需 DSH_RPC_CMD 环境变量）
export DSH_RPC_CMD="$PWD/scripts/start-rpc.sh"
mvn -o -pl testcase exec:java -Dexec.mainClass=com.deepseek.dsh.testcase.RpcE2e

# TS 协议验证（需 DSH_TOKEN + 后端在 8765）
export DSH_TOKEN=<token from start log>
node testcase/e2e.ts

# 前端 chromium 交互（需 playwright + chromium）
python3 testcase/frontend-e2e.py
```

### 8.3 认证握手

所有 e2e 脚本自动从后端日志解析启动令牌 → `GET /?token=` 换 cookie → 后续 `/api` 调用带 `-b cookie.jar`。共享路径：`testcase/.auth/`。

### 8.4 单元测试

```bash
mvn test              # 全模块
mvn -o test           # 离线（已缓存依赖）
mvn -pl dsh-web test  # 仅 dsh-web
```

---

## 9. 认证机制

后端 `/api/**` 和 `/ws/**` 需要浏览器会话 cookie 认证：

1. 后端启动时生成随机启动令牌（32B base64url）
2. 终端打印 `dsh web authentication URL: http://localhost:<port>/?token=<token>`
3. 浏览器访问该 URL → 303 换发 `dsh-auth-<sha256(authority)>` cookie（HMAC-SHA256 签名）
4. 后续所有 `/api` + `/ws` 请求凭 cookie 认证
5. 签名密钥持久化到 `~/.dsh/browser-session.json`（跨重启存活）

### 程序化认证（e2e / 脚本）

```bash
# 1. 从日志获取 token
TOKEN=$(grep -oE 'token=[A-Za-z0-9_-]+' /path/to/server.log | head -1 | cut -d= -f2)

# 2. 换 cookie
curl -s -o /dev/null "http://localhost:8765/?token=$TOKEN" -c cookie.jar

# 3. 后续调用带 cookie
curl -s -b cookie.jar http://localhost:8765/api/agent/health
```

---

## 10. 数据文件

| 文件 | 说明 |
|------|------|
| `~/.dsh/model-config.json` | 模型档案（id/displayName/apiKey/baseUrl/model/route/models） |
| `~/.dsh/settings.json` | 用户设置（agent-loop/shell/web-search-deepseek/subagent-model-selection 等命名空间） |
| `~/.dsh/workspaces.json` | 工作区注册表 |
| `~/.dsh/presets/*.yml` | 用户自定义 agent 预设 |
| `~/.dsh/sessions/*.jsonl` | 会话事件日志（事件溯源，JSONL 追加） |
| `~/.dsh/browser-session.json` | 浏览器认证签名密钥 |

> 所有数据文件已 gitignore，绝不提交 API Key。

### Windows 路径

`~` 对应 `%USERPROFILE%`（如 `C:\Users\YourName\.dsh\`）。

---

## 11. REST / SSE API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/send` | 一次性对话（回复 + 历史 + token 统计） |
| POST | `/api/agent/stream` | SSE 流式对话（session→delta*→done） |
| WS | `/ws/agent` | WebSocket（并发多 session、流式、取消） |
| WS | `/api/events.mux` | mux 下行流（session/event + projection） |
| WS | `/api/events.host` | host 下行流（host/* 帧） |
| GET | `/api/agent/health` | 健康检查 |
| GET | `/api/session.export?sessionId=…` | 导出会话 ZIP |

### apiproxy JSON-RPC 端点

两段路径 `POST /api/{channel}/{endpoint}`（如 `/api/session/create`）：

| 端点 | 说明 |
|------|------|
| `session/create` | 新建会话 |
| `session/prompt` | 发送消息（异步运行 agent turn） |
| `session/history` | 分页返回事件 + 投影 |
| `session/page` | 分页事件（throughSeq + maxMessages） |
| `session/fork` | 分叉会话 |
| `session/cancel` | 中断运行中的 turn |
| `session/rename` | 重命名 |
| `session/selectModel` | 切换模型（广播投影） |
| `session/list` | 列出全部会话 |
| `settings/describe` | 设置 schema + 当前值 |
| `settings/mutate` | 保存设置 |
| `agentPreset/list` | 预设列表 |
| `agentPreset/select` | 切换预设 |
| `llm.models` | 模型目录 |
| `skill.list` | 技能列表 |

> 所有 `/api/**` 需 cookie 认证。

---

## 12. 二次开发

### 12.1 项目结构

```
dsh-java/
├── pom.xml                  # 父 POM（41 模块）
├── dsh-core/                # 插件基座、Context、EventBus、Middleware
├── dsh-session/             # SessionLog（事件溯源）、ChatMessage、SessionManager
├── dsh-tools/               # 工具注册表、ToolPipeline、ToolSchema
├── dsh-llm/                 # LLM 适配器、ModelConfig、ModelProfileStore
├── dsh-agent/               # ReActAgentLoop、TurnObserver、Agent 接口
├── dsh-capability-shell/    # bash 工具（timeoutMs/maxOutputBytes 从 settings 读）
├── dsh-capability-fs/       # read/write/edit/glob/grep 工具
├── dsh-capability-web/      # web_search/web_fetch 工具
├── dsh-subagent/            # subagent task 工具（per-delegation 模型选择）
├── dsh-web/                 # ApiproxyController、Spring Boot、WebSocket
├── dsh-app/                 # BaseBundle、DshApplication、DshRepl、DshRpcServer
├── frontend/                # 原版 Cordis 前端（Vendored）
├── scripts/                 # 构建 + 启动脚本（sh + bat）
└── testcase/                # 端到端测试
```

### 12.2 构建流程

```bash
# 后端
scripts/build-backend.sh         # mvn clean install + dependency:build-classpath

# 前端（可选，已预构建提交）
scripts/build-frontend.sh        # pnpm install + pnpm build + deploy to static

# 启动（不编译，直接用 build-backend 的产物）
scripts/start.sh [port]
```

### 12.3 添加新工具

1. 在 `dsh-capability-*` 或 `dsh-tools` 中创建工具类：
   ```java
   public final class MyTool extends AbstractTool {
       @Override
       protected ToolSchema buildSchema() {
           return ToolSchema.builder("my_tool", "描述")
               .string("input", "输入", true)
               .build();
       }
       @Override
       protected String execute(ToolArgs args, ToolContext ctx) throws Exception {
           String input = args.requiredString("input");
           // 从 settings 读配置（可选）
           int cap = ctx.context().get(SettingsService.class)
               .map(s -> s.getAll("my-namespace").get("cap"))
               .map(v -> Integer.parseInt(v))
               .orElse(100);
           return "结果: " + input;
       }
   }
   ```

2. 在 `BaseBundle.assemble()` 中注册：
   ```java
   toolRegistry.register(new MyTool());
   ```

3. 如需 settings 配置，在 `ApiproxyController.settingsDescribe()` 添加 namespace：
   ```java
   private Map<String, Object> myNamespace() {
       // schema + value + defaults
   }
   ```

### 12.4 添加新能力缝

参照 `dsh-capability-shell` 的三件套：

1. **服务定义**（接口）：
   ```java
   public interface MyCapability extends Service { ... }
   ```

2. **服务提供者**（实现）：
   ```java
   public class MyLocalProvider extends AbstractCapabilityPlugin<MyCapability>
           implements MyCapability { ... }
   ```

3. **消费者**（工具）：
   ```java
   public class MyTool extends AbstractTool {
       private final MyCapability cap;
       // 注入 cap，在 execute 中调用
   }
   ```

在 `BaseBundle.assemble()` 中 `runner.add(new MyLocalProvider())` + `toolRegistry.register(new MyTool(...))`。

### 12.5 添加新 Agent 预设

在 `ApiproxyController.SYSTEM_PRESETS` 数组中添加：
```java
{"my-preset", "我的模式", "描述..."},
```

在 `presetSystemPrompt(id)` 添加对应系统提示词：
```java
case "my-preset" -> "You are ...";
```

### 12.6 修改前端

前端源码在 `frontend/packages/`（Vendored Cordis）。修改后需重建：

```bash
scripts/build-frontend.sh       # 全量构建
# 或单独构建一个包
cd frontend && pnpm --filter @deepseek-ai/dsh-client-ui-chat bundle
```

构建产物部署到 `dsh-app/src/main/resources/static/`。

### 12.7 添加插件配置卡片

在 `ApiproxyController` 中：

1. 添加 namespace builder（schema + value + 默认值）：
   ```java
   private Map<String, Object> myNamespace() {
       SettingsService s = settingsService();
       Map<String, Object> value = new LinkedHashMap<>();
       if (s != null) {
           Map<String, String> all = s.getAll("my-namespace");
           value.put("myField", all.getOrDefault("myField", "default"));
       }
       Map<String, Object> dict = new LinkedHashMap<>();
       dict.put("myField", schemaNode("string"));
       return namespaceView("my-namespace", value, schemaNode("object", "dict", dict));
   }
   ```

2. 在 `settingsDescribe()` 的 namespaces 列表中添加。

3. 在工具的 `execute` 中从 settings 读配置：
   ```java
   ctx.context().get(SettingsService.class)
       .map(s -> s.getAll("my-namespace").get("myField"))
       .orElse("default");
   ```

### 12.8 调试

```bash
# 后端日志
tail -f /tmp/dsh-backend.log    # 或启动脚本指定的日志文件

# 调试日志
java -Dlogging.level.com.deepseek.dsh=DEBUG ...

# 查看会话事件
cat ~/.dsh/sessions/<sid>.jsonl | python3 -m json.tool

# 查看模型配置
cat ~/.dsh/model-config.json | python3 -m json.tool

# 查看设置
cat ~/.dsh/settings.json | python3 -m json.tool
```

### 12.9 Windows 编码注意事项

- 所有 `.bat` 脚本为纯 ASCII 英文（编码无关）
- `pwsh` 命令前自动注入 `[Console]::OutputEncoding = UTF8`（解决中文乱码）
- `ProcessRunner` 用 UTF-8 读取子进程输出

### 12.10 贡献代码

1. `git pull` 获取最新代码
2. `scripts/build-backend.sh` 编译
3. `mvn test` 跑单元测试
4. `bash testcase/run-all.sh` 跑 e2e
5. 提交时**不要**提交 `.env` / `model-config.json` / `sessions/` 等含密钥的文件

---

## 附录：脚本一览

| 脚本 | 平台 | 说明 |
|------|------|------|
| `build-backend.sh` / `.bat` | Linux/Win | 编译后端 + 生成 classpath |
| `build-frontend.sh` / `.bat` | Linux/Win | 构建前端 + 部署 static |
| `start.sh` / `.bat` | Linux/Win | 启动 Web 服务 |
| `start-cli.sh` / `.bat` | Linux/Win | 启动 CLI REPL |
| `start-rpc.sh` / `.bat` | Linux/Win | 启动 RPC 服务 |
| `start-acp.sh` / `.bat` | Linux/Win | 启动 ACP 服务 |
| `testcase/run-all.sh` / `.bat` | Linux/Win | 全量 e2e 测试 |
| `testcase/web-e2e.sh` / `.bat` | Linux/Win | HTTP/SSE 契约测试 |
