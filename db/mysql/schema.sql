-- =============================================================================
-- dsh 数据库表结构（MySQL 8.0+）
-- 用途：把 session 日志、上下文、系统提示词注入、用户属性(appid 维度)持久化到 DB。
-- 运行时：通过 HTTP header 标记（X-DSH-AppId / X-DSH-Config-Source）决定是否从
--         DB 取配置（详见 README.md）。
-- 字符集：utf8mb4（COLLATE utf8mb4_unicode_ci，MariaDB/MySQL 通用；MySQL 8.0 也可用 utf8mb4_0900_ai_ci）
-- 引擎：InnoDB（事务 + 外键 + 行锁）
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. app —— 应用注册表 + appid 授权表（appid 主键）
--    不同应用（前端/客户端）各自一套 sys_prompt / context / user_attr / skill / session。
--    **授权语义**：appid 即访问凭证。请求 header X-DSH-APPID 携带的 appid 必须在此表登记
--    且 enabled=1 才允许访问；不在表里或 enabled=0 → 401/403 拒绝。default 应用为兜底。
--    access_token（可空）：若设置，请求须额外带 X-DSH-Token 匹配才放行（双因子）。
--    allowed_models（JSON 可空）：若设置，限制该 appid 可经 X-DSH-MODEL 使用的模型清单；
--    为空=不限制（可用任意已配置模型）。db_config_source 控制配置从 DB 还是文件/env 取。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `app`;
CREATE TABLE `app` (
    `appid`           VARCHAR(64)  NOT NULL COMMENT '应用标识=访问凭证（X-DSH-APPID 取值，如 web/cli/acme-bot）',
    `app_name`        VARCHAR(128) NOT NULL COMMENT '应用展示名',
    `description`     VARCHAR(512) NULL     COMMENT '应用描述',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用（1=可访问，0=停用拒绝）；授权第一道',
    `access_token`    VARCHAR(128) NULL     COMMENT '访问令牌（可空；设置则请求须带 X-DSH-Token 匹配，双因子）',
    `allowed_models`  JSON         NULL     COMMENT '允许该 appid 经 X-DSH-MODEL 使用的模型清单（空=不限制）',
    `db_config_source` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认从 DB 取配置（0=文件/env，1=DB）；可被 header X-DSH-Config-Source 覆盖',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用注册表 + appid 授权表（白名单）';


-- -----------------------------------------------------------------------------
-- 2. sys_prompt —— 系统提示词注入（单表 + type 类型标记 + appid 区分）
--    一张表承载所有"提示词块"，通过 type 区分用途：
--      'system'      → 主系统提示词（agent 角色/约束）
--      'instruction' → AGENTS.md 风格的额外指令
--      'context'     → 上下文注入块（文件引用/runtime/tmux/time 等）
--      'custom'      → 业务自定义提示词
--    priority 决定注入顺序（升序）。enabled=0 可临时停用某块。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_prompt`;
CREATE TABLE `sys_prompt` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `appid`      VARCHAR(64)  NOT NULL COMMENT '所属应用',
    `type`       VARCHAR(32)  NOT NULL COMMENT '提示词类型：system/instruction/context/custom',
    `name`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '命名标识（同 appid+type 下唯一，便于引用/覆盖）',
    `content`    LONGTEXT     NOT NULL COMMENT '提示词正文',
    `priority`   INT          NOT NULL DEFAULT 100 COMMENT '注入顺序（升序，越小越靠前）',
    `enabled`    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_type_name` (`appid`, `type`, `name`),
    KEY `idx_appid_type` (`appid`, `type`, `enabled`, `priority`),
    CONSTRAINT `fk_sys_prompt_app` FOREIGN KEY (`appid`) REFERENCES `app`(`appid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统提示词注入（type 标记 + appid 区分）';


-- -----------------------------------------------------------------------------
-- 3. app_context —— 上下文注入（appid 区分）
--    存放可注入到上下文窗口的结构化数据，与 sys_prompt(type=context) 互补：
--    本表偏"数据型"（文件引用清单/runtime 变量/tmux 会话快照等），sys_prompt 偏"文本块"。
--    ctx_type 对齐代码库 ContextPlugin 分类（file_ref/runtime/tmux/time/workspace/session）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `app_context`;
CREATE TABLE `app_context` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `appid`      VARCHAR(64)  NOT NULL COMMENT '所属应用',
    `ctx_type`   VARCHAR(32)  NOT NULL COMMENT '上下文类型：file_ref/runtime/tmux/time/workspace/session',
    `ctx_key`    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '上下文键（如文件路径/变量名；可空）',
    `content`    LONGTEXT     NULL     COMMENT '上下文值（文本或 JSON）',
    `enabled`    TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_ctx` (`appid`, `ctx_type`, `ctx_key`),
    KEY `idx_appid_ctxtype` (`appid`, `ctx_type`, `enabled`),
    CONSTRAINT `fk_app_context_app` FOREIGN KEY (`appid`) REFERENCES `app`(`appid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文注入（appid 区分）';


-- -----------------------------------------------------------------------------
-- 4. app_user_attr —— 用户属性参数（appid 维度：用户提示词 + 技能引用 + 扩展参数）
--    "不同应用有不同的用户提示词和 skill"，这些用户属性参数单独一张表。
--    每应用一行（UNIQUE appid），承载：用户提示词正文 + 该应用的技能引用 + 其它标量参数。
--    技能正文本身存 app_skill（多行）；此处 skill_refs 存引用清单（JSON 数组）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `app_user_attr`;
CREATE TABLE `app_user_attr` (
    `appid`        VARCHAR(64) NOT NULL COMMENT '所属应用（一应用一行）',
    `user_prompt`  LONGTEXT    NULL     COMMENT '该应用的用户提示词（叠加在系统提示词之后/用户消息之前）',
    `skill_refs`   JSON       NULL     COMMENT '该应用启用的技能引用清单，如 ["code-review","commit-helper"]',
    `extra_params` JSON       NULL     COMMENT '其它用户属性参数（温度/max_tokens/预设等扩展）',
    `created_at`   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`),
    CONSTRAINT `fk_user_attr_app` FOREIGN KEY (`appid`) REFERENCES `app`(`appid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户属性参数（appid 维度：用户提示词 + 技能引用）';


-- -----------------------------------------------------------------------------
-- 5. app_skill —— 应用技能（appid 维度，多技能）
--    每应用可挂多个技能（Markdown 正文 + 元数据）。与 app_user_attr.skill_refs 配合：
--    app_user_attr 决定"启用哪些"，本表存"技能正文"。
--    model_invocable/user_invocable 对齐代码库 SkillInvocationPolicy（模型可调用/用户可调用）。
--    source/provider/resource_base/path 对齐 SkillDefinition 的发现来源/提供者/资源基址/文件路径。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `app_skill`;
CREATE TABLE `app_skill` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `appid`       VARCHAR(64)  NOT NULL COMMENT '所属应用',
    `skill_name`  VARCHAR(128) NOT NULL COMMENT '技能名（同 appid 下唯一）',
    `description` VARCHAR(512) NULL     COMMENT '技能描述（对齐 SkillDefinition.description）',
    `when_to_use` VARCHAR(512) NULL     COMMENT '何时使用（对齐 SkillDefinition.whenToUse）',
    `content`     LONGTEXT     NOT NULL COMMENT '技能正文（Markdown / <skill_content> 块）',
    `model_invocable` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '模型可调用（对齐 SkillInvocationPolicy.modelInvocable）',
    `user_invocable`  TINYINT(1) NOT NULL DEFAULT 1 COMMENT '用户可调用（对齐 SkillInvocationPolicy.userInvocable）',
    `source`      VARCHAR(128) NULL     COMMENT '发现来源（对齐 SkillDefinition.source）',
    `provider`    VARCHAR(128) NULL     COMMENT '提供者名（对齐 SkillDefinition.provider）',
    `resource_base` VARCHAR(512) NULL   COMMENT '可选相对资源基址（对齐 SkillDefinition.resourceBase）',
    `path`        VARCHAR(512) NULL     COMMENT '可选文件路径（对齐 SkillDefinition.path）',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_skill` (`appid`, `skill_name`),
    KEY `idx_appid_enabled` (`appid`, `enabled`),
    CONSTRAINT `fk_app_skill_app` FOREIGN KEY (`appid`) REFERENCES `app`(`appid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用技能（appid 维度，多技能）';


-- -----------------------------------------------------------------------------
-- 6. session —— 会话元数据（appid + userid 区分）
--    session_id 对齐代码库 UUID 字符串。title/cwd 冗余便于按应用/用户查询/列表。
--    appid：所属应用；userid：会话所属用户（同一应用多用户隔离）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `session`;
CREATE TABLE `session` (
    `session_id` VARCHAR(64)  NOT NULL COMMENT '会话 ID（UUID，对齐代码库 SessionId）',
    `appid`      VARCHAR(64)  NOT NULL COMMENT '所属应用',
    `userid`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '会话所属用户（同一应用多用户隔离；空串=匿名）',
    `reasoning`  VARCHAR(8)   NOT NULL DEFAULT 'auto' COMMENT '推理标记：true/false/auto（auto=模型默认，对齐 SessionLog.reasoning）',
    `model_id`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '本会话使用的模型 id（空=活跃档案模型，对齐 SessionLog.modelId）',
    `workspace_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '所属工作区 id（空=不属于任何工作区，对齐 SessionLog.workspaceId）',
    `title`      VARCHAR(256) NULL     COMMENT '会话标题（生成/自定义）',
    `cwd`        VARCHAR(512) NULL     COMMENT '会话工作目录',
    `status`     VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT '状态：active/archived/deleted',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`),
    KEY `idx_app_user_status` (`appid`, `userid`, `status`, `updated_at`),
    KEY `idx_appid_status` (`appid`, `status`, `updated_at`),
    KEY `idx_model` (`model_id`),
    KEY `idx_workspace` (`workspace_id`),
    CONSTRAINT `fk_session_app` FOREIGN KEY (`appid`) REFERENCES `app`(`appid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话元数据（appid + userid + reasoning + model_id 区分）';


-- -----------------------------------------------------------------------------
-- 7. session_event —— 会话事件日志（session 日志入库）
--    对齐代码库 SessionEvent：每条事件一行（user/message / assistant/message /
--    tool/call / tool/result / turn/start / step/* 等）。
--    (session_id, seq) 唯一，保证可重放。appid/userid 冗余列便于按应用/用户统计/清理。
--    data 存事件 data 字段（JSON）。surface_op 标记前端是否渲染（如 'append'）。
--    time 存毫秒时间戳（对齐 SessionEvent.time）。
--    lineage_parent_session / lineage_depth 对齐 SessionEvent.Lineage（fork/subagent 父会话溯源）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `session_event`;
CREATE TABLE `session_event` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `session_id`  VARCHAR(64)  NOT NULL COMMENT '所属会话',
    `appid`       VARCHAR(64)  NOT NULL COMMENT '冗余：所属应用（便于按应用查询/清理）',
    `userid`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '冗余：所属用户（便于按用户查询/清理）',
    `seq`         INT          NOT NULL COMMENT '事件序号（会话内单调递增）',
    `event_type`  VARCHAR(64)  NOT NULL COMMENT '事件类型：user/message/assistant/message/tool/call/tool/result/turn/start/step/...',
    `data`        JSON         NULL     COMMENT '事件 data 字段（消息内容/工具参数等）',
    `surface_op`  VARCHAR(32)  NULL     COMMENT '前端渲染标记：append/replace/null',
    `lineage_parent_session` VARCHAR(64) NULL COMMENT 'fork/subagent 父会话 ID（对齐 Lineage.parentSession）',
    `lineage_depth` INT       NOT NULL DEFAULT 0 COMMENT '委派深度（对齐 Lineage.delegationDepth，根=0）',
    `reasoning`  VARCHAR(8)  NOT NULL DEFAULT 'auto' COMMENT '冗余：本事件所属会话的推理标记（true/false/auto）',
    `model_id`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '冗余：本事件所用模型 id（便于按模型统计）',
    `workspace_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '冗余：本事件所属会话的工作区 id',
    `time`        BIGINT UNSIGNED NOT NULL COMMENT '事件时间（毫秒时间戳，对齐 SessionEvent.time）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_seq` (`session_id`, `seq`),
    KEY `idx_session_time` (`session_id`, `time`),
    KEY `idx_appid_type_time` (`appid`, `event_type`, `time`),
    KEY `idx_app_user_time` (`appid`, `userid`, `time`),
    CONSTRAINT `fk_event_session` FOREIGN KEY (`session_id`) REFERENCES `session`(`session_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话事件日志（session 日志入库）';

-- -----------------------------------------------------------------------------
-- 8. model_profile —— 模型档案（model-config.json 的 DB 化，appid 区分）
--    对齐 ModelProfile：每应用可有多套模型档案（不同 provider/key/模型），其一为活跃。
--    is_active=1 表示该 appid 当前活跃档案（每 appid 至多 1，由应用层 ModelProfileStore 维护）。
--    api_key 敏感：DB 须 gitignore，绝不提交；运行时由 ModelProfileStore.syncRuntime() 读出。
--    X-DSH-MODEL header 可单次覆盖（不写库），activeId 为该 appid 默认模型。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `model_profile`;
CREATE TABLE `model_profile` (
    `id`          VARCHAR(64)  NOT NULL COMMENT '档案 id（UUID，对齐 ModelProfile.id）',
    `appid`       VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '所属应用（default=全局）',
    `display_name` VARCHAR(128) NOT NULL COMMENT '展示名',
    `api_key`     VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'API Key（敏感，库须 gitignore）',
    `base_url`    VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'OpenAI 兼容端点',
    `model`       VARCHAR(128) NOT NULL COMMENT '模型名',
    `route`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '路由标记',
    `models`      JSON         NULL     COMMENT '可用模型清单（对齐 ModelProfile.models）',
    `is_active`   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否该 appid 活跃档案（每 appid 至多 1，应用层维护）',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '展示顺序',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`, `id`),
    KEY `idx_appid_active` (`appid`, `is_active`, `sort_order`),
    CONSTRAINT `fk_model_profile_app` FOREIGN KEY (`appid`) REFERENCES `app`(`appid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型档案（model-config.json DB 化，appid 区分）';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 初始化数据（appid 授权白名单）：登记 default / web / cli 三个应用，均 enabled=1。
-- 运行时：请求 X-DSH-APPID 不在此表或 enabled=0 → 拒绝访问（401/403）。
-- =============================================================================
INSERT INTO `app` (`appid`, `app_name`, `description`, `enabled`, `db_config_source`) VALUES
    ('default', '默认应用', 'dsh 兜底应用（header 未带 appid 时使用；文件/env 配置回退）', 1, 0),
    ('web',     'Web 前端', '浏览器前端（Cordis SPA）', 1, 0),
    ('cli',     'CLI 客户端', '命令行客户端', 1, 0)
ON DUPLICATE KEY UPDATE `app_name` = VALUES(`app_name`), `description` = VALUES(`description`),
                          `enabled` = VALUES(`enabled`), `updated_at` = CURRENT_TIMESTAMP;
