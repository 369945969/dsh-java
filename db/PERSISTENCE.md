# 持久保留（Persistence）

dsh 会话的持久保留架构——从 HTTP header 注入到 SessionLog，再到文件/数据库后端。
本文描述"保留什么、怎么保留、如何流转"。

> 配套：表结构见 [`mysql/schema.sql`](mysql/schema.sql)，表说明见 [`mysql/README.md`](mysql/README.md)。

---

## 1. 保留什么

每个会话（session）保留以下信息，跨重启可恢复：

| 类别 | 字段 | 来源 | 说明 |
|---|---|---|---|
| **标识** | `session_id` | UUID | 对齐代码库 `SessionId`，主键 |
| **应用** | `appid` | `X-DSH-APPID` header / env | 应用维度隔离，默认 `default` |
| **用户** | `userid` | `X-DSH-USERID` header / env | 用户维度隔离，默认 `""`（匿名） |
| **推理标记** | `reasoning` | `X-DSH-REASONING` header / env | `true`/`false`/`auto`，`auto`=模型默认 |
| **模型** | `model_id` | `X-DSH-MODEL` header / env | 本会话使用的模型 id，空=活跃档案模型 |
| **元数据** | `title` / `cwd` / `status` | 生成/传入 | 标题、工作目录、状态(active/archived/deleted) |
| **事件流** | `session_event[]` | agent loop 记录 | user/message、assistant/message、tool/call、tool/result、turn/start、step/* … |
| **溯源** | `lineage_parent_session` / `lineage_depth` | fork/subagent | 父会话 ID + 委派深度（对齐 `SessionEvent.Lineage`） |
| **token 用量** | `inputTokens` / `outputTokens` / `sessionTokens` | `TokenMeterService` delta | 累计输入/输出/总，供客户端判断何时手动压缩 |

配置类（按 appid 区分，非会话级）保留在 `sys_prompt` / `app_context` / `app_user_attr` / `app_skill` 表，详见 mysql/README.md。

---

## 2. 持久化层次

```
HTTP header (X-DSH-*)
      │  Web/WS 层注入
      ▼
SessionAuth (ThreadLocal, dsh-core)     ← 请求级隐式上下文
      │  SessionManager 创建会话时盖章
      ▼
SessionLog (内存, dsh-session)          ← 单会话事件日志 + 元数据 + token
      │  SessionStore.append(event)
      ▼
JsonlSessionStore (文件, 当前)           ~/.dsh/sessions/<sid>.jsonl
      ║  （未来）
      ▼
MySQLSessionStore (DB, 设计中)          dsh-java.session / session_event
```

- **内存层 `SessionLog`**：单会话的事件列表 + appid/userid/reasoning/modelId + token 累计。`SessionManager` 持活跃会话注册表（`ConcurrentMap<SessionId, SessionLog>`）。
- **文件层 `JsonlSessionStore`（当前实现）**：每事件一行 JSON 追加到 `<sid>.jsonl`，崩溃安全；重启按行重放重建。
- **DB 层（设计完成，待接代码）**：`SessionStore` 新增 MySQL 实现，`append`→`insert session_event`，`load`→`select order by seq`，`listAll`→`select session_id`，`delete`→`delete session+event`。

---

## 3. Header → SessionLog → DB 流转

请求方在 HTTP header 传标记（缺失走默认）：

| Header | 默认 | 作用 |
|---|---|---|
| `X-DSH-APPID` | `default` | 应用隔离 |
| `X-DSH-USERID` | `""` | 用户隔离 |
| `X-DSH-REASONING` | `auto` | `true`/`false` 强制开/关 thinking，`auto` 不干预 |
| `X-DSH-MODEL` | `""` | 覆盖请求模型（空=活跃档案模型） |

**注入点**：
- **Web** `AgentController.send`：`@RequestHeader` 读 4 个 header → `SessionAuth.set(...)`（finally 里 `clear()`）。
- **WS** `SessionAuthHandshakeInterceptor`：握手时把 header 拷进 attributes（WS 连接后无 header）；`AgentWebSocketHandler.runTurn` 每个 turn 从 attributes 注入 `SessionAuth`。
- **RPC** `DshRpcServer.session.create`/`session.prompt`：从 env（`DSH_APP_ID`/`DSH_USER_ID`/`DSH_REASONING`/`DSH_MODEL_ID`）注入（stdio 无 header）。

**盖章**：`SessionManager.create()` / `getOrCreate()` 创建会话时 `SessionLog.setAuth(appid, userid, reasoning, modelId)`，取自 `SessionAuth`（同线程 ThreadLocal，与 `SessionCwd` 同款隐式传递）。

**生效**：
- `reasoning` → `DeepSeekLlmAdapter.buildRequestBody`：`true`→`enable_thinking:true`，`false`→`false`，`auto`→不写字段。
- `modelId` → `buildRequestBody`：非空时覆盖 `body.put("model", modelId)`，空则用 `ModelConfig` 活跃档案模型。

**回显**：`/api/agent/send` 响应、`session.list`、RPC `session.create`/`session.prompt` 均回显 appid/userid/reasoning/modelId/tokens，供客户端校验。

---

## 4. 会话事件流（session_event）

对齐代码库 `SessionEvent`，每条事件一行/一记录，`(session_id, seq)` 唯一保证可重放重连恢复：

| event_type | 何时记录 | data 示例 |
|---|---|---|
| `user/message` | 用户消息（web send / ws runTurn / RPC prepareTurn） | `{id, content, source}` |
| `assistant/message` | 助手回复定稿 | `{message:{id,content}, turn, step}` |
| `assistant/chunk` | 流式增量（仅实时，落盘可省） | delta 文本 |
| `tool/call` | 模型发起工具调用 | `{callId, name, arguments}` |
| `tool/result` | 工具返回 | `{callId, message:{content}}` |
| `turn/start` `turn/end` | 回合边界 | `{turn}` |
| `step/start` `step/end` | ReAct 步边界 | `{turn, step}` |
| `request/header` `request/context` | 系统提示/上下文注入快照 | 头/注入清单 |

`surface_op` 标记前端是否渲染（`append`=追加显示，`null`=不渲染）。
`lineage_parent_session`/`lineage_depth`：fork 出的子会话或 subagent 委派的事件带父会话 ID + 深度，便于溯源。

---

## 5. Token 计量与手动压缩

**累计**：`SessionLog.addTokens(in, out)` 由 Web/WS/RPC 在 turn 结束后调用，取 `TokenMeterService` 全局 `totalPromptTokens()`/`totalCompletionTokens()` 的 delta（turn 前-后差值）。

- `inputTokens` = 累计 prompt_tokens（输入）
- `outputTokens` = 累计 completion_tokens（输出，**含 reasoning_tokens**——模型发 `reasoning_content` 则计入；`X-DSH-REASONING:true` 可强制开 thinking 让模型发推理）
- `sessionTokens` = `inputTokens + outputTokens`（本会话总，≠ 全局 totalTokens）

**手动压缩**：客户端读响应/`session.list` 的 `sessionTokens`，超过阈值时调 `session.compact`（`maxTokens` 参数）。`CompactionService.compact(messages, maxTokens)` 裁剪/摘要历史，返回 `before`/`after` 消息数。`sessionTokens` 随压缩下降（取决于 provider 实现，小对话不一定立即缩）。

```
客户端:  读 sessionTokens ──> 超阈值? ──> POST session.compact ──> before/after
```

---

## 6. 当前实现状态 vs 未来

| 项 | 当前 | 未来（DB 化） |
|---|---|---|
| 事件存储 | `JsonlSessionStore`（~/.dsh/sessions/*.jsonl） | `MySQLSessionStore`（session_event 表） |
| appid/userid/reasoning/modelId | SessionLog 内存字段 + 响应回显 | 落 session + session_event 列 |
| token | SessionLog 内存累计 + 响应回显 | 落 session 表（可周期 flush） |
| 会话删除 | `SessionManager.delete`（active 移除 + 文件删除） | `delete session + session_event`（CASCADE） |
| 配置（sys_prompt/context/skill） | 文件（dataDir/skills/*.md、AGENTS.md、model-config.json） | `sys_prompt`/`app_context`/`app_user_attr`/`app_skill` 表，header `X-DSH-Config-Source:db` 触发 |

**DB 化接线点**（待实现）：
- `SessionStore` 新增 MySQL 实现，`BaseBundle` 装配时按配置选 jsonl/MySQL。
- `DbConfigSource` 插件：读 `X-DSH-Config-Source`/`X-DSH-APPID` → 按 appid 从 DB 加载 sys_prompt/app_context/app_user_attr/app_skill → 注入 agent 装配（替代/叠加文件加载）。
- `SessionManager.delete` 已就绪（active + store.delete）。

---

## 7. 部署

```bash
# 库 + 表（MariaDB / MySQL 8）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS \`dsh-java\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p dsh-java < db/mysql/schema.sql
```

环境变量（RPC）/ header（Web/WS）同 §3。模型配置仍取 `~/.dsh/model-config.json` 活跃档案（`X-DSH-MODEL` 可单次覆盖）。
