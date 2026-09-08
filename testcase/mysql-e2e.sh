#!/usr/bin/env bash
# MySQL 存储模式（DSH_STORAGE=mysql）端到端验证 —— 覆盖从 DB 读写的三块能力：
#   1) 模型档案从 DB 读（model_profile）—— 用 X-DSH-MODEL 覆盖 + 默认活跃档案
#   2) session 日志写 DB（session_event）—— send 后查表
#   3) 系统提示词/上下文从 DB 读（sys_prompt + app_context）—— agent 行为反映 DB 标记
#   4) 技能从 DB 读（app_skill）—— skill.list / skill.get 返回 DB 技能
#
# 前置：DSH_STORAGE=mysql 的 web 服务已启动（scripts/start.sh）+ 已 seed（db/mysql/seed-config.sh）。
# 用法：DSH_TOKEN=xxx testcase/mysql-e2e.sh [port]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"
BASE="http://localhost:$PORT"
DSH_TOKEN="${DSH_TOKEN:-ECkvAL8rG-BYj_ex_B8hleaq8mk88ncheFEor1SoDkg}"
DB="${DSH_DB:-dsh-java}"
MYSQL_USER="${MYSQL_USER:-root}"
PASS=0; FAIL=0
pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1 — $2"; FAIL=$((FAIL+1)); }

COOKIE="$ROOT/testcase/.auth/cookie.jar"
mkdir -p "$(dirname "$COOKIE")"
curl -s -o /dev/null "$BASE/?token=$DSH_TOKEN" -c "$COOKIE"
curl -s -b "$COOKIE" "$BASE/api/agent/health" | grep -q '"status":"ok"' || { echo "[mysql-e2e] 服务未就绪"; exit 1; }

echo "[mysql-e2e] target: $BASE (DSH_STORAGE=mysql)"

# ---- 1) 模型档案从 DB 读（model_profile）+ X-DSH-MODEL 覆盖 ----
M=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -H "X-DSH-MODEL: glm-5.2" -d '{"message":"Reply with the single word OK."}' 2>/dev/null)
if echo "$M" | jq -e '.modelId == "glm-5.2"' >/dev/null 2>&1; then
  pass "model from DB + X-DSH-MODEL override (modelId=glm-5.2)"
else
  fail "model from DB" "modelId=$(echo "$M" | jq -r '.modelId // empty')"
fi

# ---- 2) session 日志写 DB（session_event 表有行）----
SID=$(echo "$M" | jq -r '.sessionId // empty')
EVTS=$(mysql -u "$MYSQL_USER" -p"$MYSQL_PWD" -N -e "SELECT COUNT(*) FROM session_event WHERE session_id='$SID'" "$DB" 2>/dev/null || echo 0)
if [ "${EVTS:-0}" -ge 2 ]; then
  pass "session log written to DB (session_event has $EVTS rows)"
else
  fail "session log to DB" "events=$EVTS for sid=$SID"
fi

# ---- 3) 系统提示词从 DB 读（sys_prompt：agent 应提 database）----
SP=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -d '{"message":"In one short sentence, what is the source of your system prompt?"}' 2>/dev/null)
if echo "$SP" | tr '[:upper:]' '[:lower:]' | grep -qi "database\|db"; then
  pass "sys_prompt from DB (agent mentions database)"
else
  fail "sys_prompt from DB" "reply: $(echo "$SP" | jq -r '.reply // empty' | head -c 120)"
fi

# ---- 3b) 上下文从 DB 读（app_context：agent 应提 DB-CONTEXT/RUNTIME 标记）----
CT=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -d '{"message":"In one short sentence, is MySQL storage enabled for this project?"}' 2>/dev/null)
if echo "$CT" | tr '[:upper:]' '[:lower:]' | grep -qi "mysql\|yes\|enabled"; then
  pass "app_context from DB (agent reflects DB-CONTEXT-MARKER)"
else
  fail "app_context from DB" "reply: $(echo "$CT" | jq -r '.reply // empty' | head -c 120)"
fi

# ---- 4) 技能从 DB 读（app_skill：skill.list / skill.get）----
SL=$(curl -s -b "$COOKIE" -X POST "$BASE/api/skill.list" -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{}}' 2>/dev/null)
if echo "$SL" | jq -r '.result.value.skills[].name' 2>/dev/null | grep -q "^code-review$"; then
  pass "app_skill from DB (skill.list has code-review)"
else
  fail "app_skill skill.list" "$(echo "$SL" | head -c 150)"
fi
SG=$(curl -s -b "$COOKIE" -X POST "$BASE/api/skill.get" -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{"name":"code-review"}}' 2>/dev/null)
if echo "$SG" | jq -e '.result.value.found == true' >/dev/null 2>&1 && echo "$SG" | grep -q 'skill_content'; then
  pass "app_skill from DB (skill.get code-review found+rendered)"
else
  fail "app_skill skill.get" "$(echo "$SG" | head -c 150)"
fi

echo
echo "[mysql-e2e] 结果: $PASS 通过, $FAIL 失败"
[ "$FAIL" -eq 0 ]
