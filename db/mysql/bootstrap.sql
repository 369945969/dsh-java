-- =============================================================================
-- dsh 启动引导（非破坏性）：CREATE TABLE IF NOT EXISTS + seed，不 DROP。
-- 由 start.sh / start-rpc.sh 在 DSH_STORAGE=mysql 时启动前执行（幂等，可重复跑）。
-- 全量重置（含 DROP）用 schema.sql。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `app` (
    `appid`           VARCHAR(64)  NOT NULL,
    `app_name`        VARCHAR(128) NOT NULL,
    `description`     VARCHAR(512) NULL,
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `access_token`    VARCHAR(128) NULL,
    `allowed_models`  JSON         NULL,
    `db_config_source` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_prompt` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `appid` VARCHAR(64) NOT NULL, `type` VARCHAR(32) NOT NULL,
    `name` VARCHAR(128) NOT NULL DEFAULT '', `content` LONGTEXT NOT NULL,
    `priority` INT NOT NULL DEFAULT 100, `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_type_name` (`appid`,`type`,`name`),
    KEY `idx_appid_type` (`appid`,`type`,`enabled`,`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `app_context` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `appid` VARCHAR(64) NOT NULL, `ctx_type` VARCHAR(32) NOT NULL,
    `ctx_key` VARCHAR(128) NOT NULL DEFAULT '', `content` LONGTEXT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_ctx` (`appid`,`ctx_type`,`ctx_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `app_user_attr` (
    `appid` VARCHAR(64) NOT NULL,
    `user_prompt` LONGTEXT NULL, `skill_refs` JSON NULL, `extra_params` JSON NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `app_skill` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `appid` VARCHAR(64) NOT NULL, `skill_name` VARCHAR(128) NOT NULL,
    `description` VARCHAR(512) NULL, `when_to_use` VARCHAR(512) NULL,
    `content` LONGTEXT NOT NULL,
    `model_invocable` TINYINT(1) NOT NULL DEFAULT 0, `user_invocable` TINYINT(1) NOT NULL DEFAULT 1,
    `source` VARCHAR(128) NULL, `provider` VARCHAR(128) NULL,
    `resource_base` VARCHAR(512) NULL, `path` VARCHAR(512) NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_skill` (`appid`,`skill_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `model_profile` (
    `id` VARCHAR(64) NOT NULL, `appid` VARCHAR(64) NOT NULL DEFAULT 'default',
    `display_name` VARCHAR(128) NOT NULL, `api_key` VARCHAR(256) NOT NULL DEFAULT '',
    `base_url` VARCHAR(256) NOT NULL DEFAULT '', `model` VARCHAR(128) NOT NULL,
    `route` VARCHAR(64) NOT NULL DEFAULT '',  VARCHAR(64) NOT NULL DEFAULT '', `models` JSON NULL,
    `is_active` TINYINT(1) NOT NULL DEFAULT 0, `sort_order` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`,`id`),
    KEY `idx_appid_active` (`appid`,`is_active`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `session` (
    `session_id` VARCHAR(64) NOT NULL, `appid` VARCHAR(64) NOT NULL,
    `userid` VARCHAR(64) NOT NULL DEFAULT '', `reasoning` VARCHAR(8) NOT NULL DEFAULT 'auto',
    `model_id` VARCHAR(64) NOT NULL DEFAULT '', `workspace_id` VARCHAR(64) NOT NULL DEFAULT '', `title` VARCHAR(256) NULL,
    `cwd` VARCHAR(512) NULL, `status` VARCHAR(16) NOT NULL DEFAULT 'active',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `session_event` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(64) NOT NULL, `appid` VARCHAR(64) NOT NULL DEFAULT 'default',
    `userid` VARCHAR(64) NOT NULL DEFAULT '', `seq` INT NOT NULL,
    `event_type` VARCHAR(64) NOT NULL, `data` JSON NULL, `surface_op` VARCHAR(32) NULL,
    `lineage_parent_session` VARCHAR(64) NULL, `lineage_depth` INT NOT NULL DEFAULT 0,
    `reasoning` VARCHAR(8) NOT NULL DEFAULT 'auto', `model_id` VARCHAR(64) NOT NULL DEFAULT '', `workspace_id` VARCHAR(64) NOT NULL DEFAULT '',
    `time` BIGINT UNSIGNED NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_seq` (`session_id`,`seq`),
    KEY `idx_session_time` (`session_id`,`time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;

-- seed appid 白名单（ON DUPLICATE KEY 不破坏现有 enabled/access_token 等设置）
INSERT INTO `app` (`appid`, `app_name`, `description`, `enabled`, `db_config_source`) VALUES
    ('default', '默认应用', 'dsh 兜底应用（header 未带 appid 时使用）', 1, 0),
    ('web',     'Web 前端', '浏览器前端', 1, 0),
    ('cli',     'CLI 客户端', '命令行客户端', 1, 0)
ON DUPLICATE KEY UPDATE `app_name` = VALUES(`app_name`), `description` = VALUES(`description`), `updated_at` = CURRENT_TIMESTAMP;
