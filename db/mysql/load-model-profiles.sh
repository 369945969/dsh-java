#!/usr/bin/env bash
# 把 ~/.dsh/model-config.json 的模型档案导入 dsh-java.model_profile 表。
# 幂等：CREATE TABLE IF NOT EXISTS + DELETE 该 appid 旧档案 + 重新 INSERT。
# api_key 敏感：只写临时 SQL 文件（不回显 stdout），用完即删。
#
# 用法：
#   MYSQL_PWD=xxx bash db/mysql/load-model-profiles.sh
# 可选环境变量：
#   DSH_DB            库名（默认 dsh-java）
#   MYSQL_USER        用户（默认 root）
#   DSH_MODEL_CONFIG  配置文件（默认 ~/.dsh/model-config.json）
#   DSH_APPID         归属应用（默认 default）
set -euo pipefail

DB="${DSH_DB:-dsh-java}"
USER="${MYSQL_USER:-root}"
CONFIG="${DSH_MODEL_CONFIG:-$HOME/.dsh/model-config.json}"
APPID="${DSH_APPID:-default}"

command -v jq >/dev/null 2>&1 || { echo "[load-models] 需要 jq"; exit 1; }
[ -f "$CONFIG" ] || { echo "[load-models] 配置不存在: $CONFIG"; exit 1; }
[ -n "${MYSQL_PWD:-}" ] || { echo "[load-models] 请设 MYSQL_PWD 环境变量（数据库密码）"; exit 1; }

# SQL 字符串转义：单引号翻倍
esc() { printf '%s' "$1" | sed "s/'/''/g"; }

MYSQL=(mysql -u "$USER" -p"$MYSQL_PWD" "$DB")
echo "[load-models] DB=$DB CONFIG=$CONFIG APPID=$APPID"

# 1) 建表（幂等）
echo "[load-models] 确保表存在..."
"${MYSQL[@]}" <<'SQL'
CREATE TABLE IF NOT EXISTS `model_profile` (
    `id`           VARCHAR(64)  NOT NULL,
    `appid`        VARCHAR(64)  NOT NULL DEFAULT 'default',
    `display_name` VARCHAR(128) NOT NULL,
    `api_key`      VARCHAR(256) NOT NULL DEFAULT '',
    `base_url`     VARCHAR(256) NOT NULL DEFAULT '',
    `model`        VARCHAR(128) NOT NULL,
    `route`        VARCHAR(64)  NOT NULL DEFAULT '',
    `models`       JSON         NULL,
    `is_active`    TINYINT(1)   NOT NULL DEFAULT 0,
    `sort_order`   INT          NOT NULL DEFAULT 0,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`appid`, `id`),
    KEY `idx_appid_active` (`appid`, `is_active`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型档案';
SQL

# 2) 生成导入 SQL：jq 输出 TSV，bash 逐行转义建 INSERT
SQLFILE="$(mktemp)"
trap 'rm -f "$SQLFILE"' EXIT
ACTIVE="$(jq -r '.activeId // ""' "$CONFIG")"
{
  echo "SET NAMES utf8mb4;"
  echo "DELETE FROM \`model_profile\` WHERE appid='$(esc "$APPID")';"
  jq -r --arg active "$ACTIVE" '
    .profiles[]?
    | [ (.id // ""),
        (.displayName // ""),
        (.apiKey // ""),
        (.baseUrl // ""),
        (.model // ""),
        (.route // ""),
        ((.models // []) | if length > 0 then tojson else "" end),
        (if (.id // "") == $active then "1" else "0" end) ]
    | @tsv
  ' "$CONFIG" | tr '\t' '\037' | while IFS=$'\037' read -r id name key base model route mjson active_flag; do
    if [ -n "$mjson" ]; then mj="'$(esc "$mjson")'"; else mj="NULL"; fi
    printf "INSERT INTO \`model_profile\` (id,appid,display_name,api_key,base_url,model,route,models,is_active,sort_order) VALUES ('%s','%s','%s','%s','%s','%s','%s',%s,%s,0) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),api_key=VALUES(api_key),base_url=VALUES(base_url),model=VALUES(model),route=VALUES(route),models=VALUES(models),is_active=VALUES(is_active);\n" \
      "$(esc "$id")" "$(esc "$APPID")" "$(esc "$name")" "$(esc "$key")" "$(esc "$base")" "$(esc "$model")" "$(esc "$route")" "$mj" "$active_flag"
  done
} > "$SQLFILE"

LINES=$(grep -c '^INSERT' "$SQLFILE" || true)
echo "[load-models] 生成 $LINES 条 INSERT，导入中（api_key 不回显）..."
"${MYSQL[@]}" < "$SQLFILE"
echo "[load-models] 完成"

# 3) 校验（api_key 脱敏）
echo "[load-models] 当前档案（api_key 已脱敏）："
"${MYSQL[@]}" -e "SELECT appid, LEFT(id,8) AS id, display_name, model, route, is_active, IF(api_key='','(空)',CONCAT(LEFT(api_key,6),'…')) AS key_preview FROM model_profile WHERE appid='$APPID' ORDER BY is_active DESC, sort_order;" 2>&1 | grep -v '^\-\-\|^\s*$'
