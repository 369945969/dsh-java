# dsh MySQL 表结构

把 session 日志、上下文、系统提示词注入、用户属性（appid 维度）持久化到 DB。

## 表清单

| 表 | 作用 | appid | userid | 类型标记 |
|---|---|---|---|---|
| `app` | 应用注册表 + appid 授权表（白名单：表内+enabled=1 才可访问） | — | — | — |
| `sys_prompt` | 系统提示词注入（system/instruction/context/custom） | ✅ | — | `type` |
| `app_context` | 上下文注入（file_ref/runtime/tmux/time/workspace/session） | ✅ | — | `ctx_type` |
| `app_user_attr` | 用户属性（用户提示词 + 技能引用 + 扩展参数） | ✅（一应用一行） | — | — |
| `app_skill` | 应用技能正文（每应用多技能） | ✅ | — | `model_invocable`/`user_invocable` |
| `session` | 会话元数据 | ✅ | ✅ | `status` |
| `session_event` | 会话事件日志（对齐 SessionEvent） | ✅（冗余） | ✅（冗余） | `event_type` |

- 配置表（app/sys_prompt/app_context/app_user_attr/app_skill）按 **appid** 区分（应用级，全应用共享）。
- 会话表（session/session_event）按 **appid + userid** 区分（用户级，同应用多用户隔离）。

外键全部 `ON DELETE CASCADE`：删 app 连带清掉其下全部配置 + 会话。

## 表关系

```
app (appid PK)
 ├─ sys_prompt      (appid, type, name)        系统提示词块
 ├─ app_context     (appid, ctx_type, ctx_key) 上下文数据
 ├─ app_user_attr   (appid)                    用户提示词 + skill_refs
 ├─ app_skill       (appid, skill_name)        技能正文
 └─ session (session_id PK, appid)
     └─ session_event (session_id, seq)        事件流
```

## 运行时：appid 授权 + header 标记决定配置来源

### appid 授权（白名单）

`app` 表即 appid 授权表。请求 header `X-DSH-APPID` 携带的 appid 必须：

1. **存在于 `app` 表**——不在表里 → 401/403 拒绝访问；
2. **`enabled=1`**——`enabled=0`（停用）→ 拒绝；
3. （可选双因子）若该 appid 的 `access_token` 非空，请求须额外带 `X-DSH-Token` 匹配。

header 未带 `X-DSH-APPID` 时走 `default` 应用（兜底，已 seed 且 enabled=1）。`allowed_models`（JSON）可限制该 appid 经 `X-DSH-MODEL` 使用的模型清单，空=不限制。

### 配置来源标记

请求方在 HTTP header 传入标记，后端据此决定"从 DB 取"还是"走原文件/env 默认"：

| Header | 含义 | 取值 |
|---|---|---|
| `X-DSH-APPID` | 当前应用=授权凭证 | appid（须在 `app` 表 + enabled=1，否则拒绝；缺失→`default`） |
| `X-DSH-USERID` | 会话用户 | 用户 id（隔离同应用多用户，默认 `""` 匿名） |
| `X-DSH-REASONING` | 推理开关 | `true`/`false`/`auto`（auto=模型默认） |
| `X-DSH-MODEL` | 单次覆盖模型 | 模型 id（须在该 appid 的 `allowed_models` 内，空=活跃档案） |
| `X-DSH-Token` | 访问令牌（双因子） | 当 appid 的 `access_token` 非空时必须匹配 |
| `X-DSH-Config-Source` | 配置来源 | `db` / `file` / `auto`（默认 `auto`：app.db_config_source 决定，0→file，1→db） |

取数顺序（当 `X-DSH-Config-Source=db` 或 `auto && app.db_config_source=1`）：
1. `sys_prompt` where appid=? and enabled=1 order by priority → 拼系统提示词
2. `app_context` where appid=? and enabled=1 → 注入上下文
3. `app_user_attr` where appid=? → 用户提示词 + skill_refs
4. `app_skill` where appid=? and skill_name in (skill_refs) and enabled=1 → 技能正文
5. 写 session 时落 `session` + `session_event`（带 appid）

`X-DSH-Config-Source=file` 或 `auto && db_config_source=0` 时回退到现有文件/env 加载（`dataDir/skills/*.md`、`AGENTS.md`、`model-config.json`）。

## 设计要点

- **系统提示词单表 + type 标记**：`sys_prompt.type` 区分 system/instruction/context/custom；`priority` 控制注入顺序；`enabled` 临时停用。一表多用途，便于按 type 检索与覆盖。
- **上下文与提示词分表**：`app_context` 偏"数据型"（文件引用/变量/tmux 快照），`sys_prompt(type=context)` 偏"文本块"；二者互补，都按 appid 区分。
- **用户属性单表**：`app_user_attr` 一应用一行，承载用户提示词 + 技能引用清单 + 扩展参数；技能正文存 `app_skill`（多行），引用清单 `skill_refs` 决定启用哪些。
- **session 日志**：`session` 存元数据（含 userid），`session_event` 存事件流（对齐 `SessionEvent`：seq/event_type/data/surface_op/time/lineage）。`(session_id, seq)` 唯一保证可重放/重连恢复。`lineage_parent_session`+`lineage_depth` 对齐 `SessionEvent.Lineage`，追踪 fork/subagent 父会话溯源。`userid` 冗余到 session_event 便于按用户统计/清理。
- **技能字段对齐 SkillDefinition**：`app_skill` 含 source/provider/resource_base/path + model_invocable/user_invocable（两个布尔，对齐 `SkillInvocationPolicy`），不丢原文件来源与调用策略。
- **appid 全局区分**：配置表 appid 应用级；会话表 appid+userid 用户级。session_event 冗余 appid+userid 便于按应用/用户查询/清理。

## 部署

```bash
# MariaDB（默认 utf8mb4_unicode_ci）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS \`dsh-java\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p dsh-java < db/mysql/schema.sql
# MySQL 8.0 可用 utf8mb4_0900_ai_ci
```

## 与代码库的对接（后续步骤，本次仅设计表结构）

- `SessionStore` 新增 MySQL 实现（`JsonlSessionStore` 保留为文件回退）：`append`→`insert session_event`，`load`→`select ... order by seq`，`listAll`→`select distinct session_id`，`delete`→`delete session + session_event`。
- `ModelProfileStore` 可选 DB 持久化（当前 `model-config.json` 仍为单实例配置，不按 appid 分）。
- 新增 `DbConfigSource` 插件：读 header → 按 appid 加载 sys_prompt/app_context/app_user_attr/app_skill → 注入 agent 装配（替代/叠加 `AgentInstructionsPlugin`/`SkillRegistry`/`FileReferenceService` 的文件加载）。
