#!/usr/bin/env bash
# Web 端端到端验证 —— 模拟前端 HTTP/SSE 交互，验证后端 Web 面（REST + SSE）。
# 方便用户接自己的前端：本脚本覆盖前端会调用的全部 HTTP 契约。
#
# 覆盖：
#   GET  /api/agent/health      健康检查
#   POST /api/agent/send        一次性对话（返回完整回复+历史）
#   POST /api/agent/stream      SSE 流式对话（session→delta*→done）
#
# 用法： testcase/web-e2e.sh [web_port]
# 模型取自 dataDir/model-config.json（网页保存的活跃档案），无需环境变量。
#
# 认证：后端 /api 需要浏览器会话 cookie。本脚本启动（或复用）服务端后，从
# start.sh 捕获的 stderr 解析启动令牌，用 GET /?token=<token> 换取
# dsh-auth cookie（curl -c cookie.jar），后续所有 /api 调用带 -b cookie.jar。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"
BASE="http://localhost:$PORT"
PASS=0; FAIL=0

# 认证共享状态（run-all.sh 与 web-e2e.sh 共用同一路径，便于复用已启动的服务端）
AUTH_DIR="$ROOT/testcase/.auth"
SRVLOG="$AUTH_DIR/server.log"
TOKEN_FILE="$AUTH_DIR/token.txt"
COOKIE="$AUTH_DIR/cookie.jar"
mkdir -p "$AUTH_DIR"

# 仅当端口未占用时启动自带服务端（用索引页探测端口，不看 /api 认证状态）
start_server=0
CODE="$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$BASE/" || true)"
if [ "$CODE" = "000" ]; then
  echo "[web-e2e] 启动 Web 服务端 (port=$PORT)..." >&2
  : > "$SRVLOG"
  "$ROOT/scripts/start.sh" "$PORT" >>"$SRVLOG" 2>&1 &
  WEB_PID=$!
  start_server=1
  trap '[ -n "${WEB_PID:-}" ] && kill "$WEB_PID" 2>/dev/null || true' EXIT INT TERM
fi

# 解析启动令牌并换取 cookie（握手）：等待 token 出现在日志里
# 正在启动本服务端则强制重握手（避免沿用上一次运行的过期 cookie）；复用已运行
# 服务端（如 run-all.sh 已启动）时，优先用现成 token/cookie，否则从日志解析。
TOKEN=""
force_handshake=0
if [ "$start_server" = "1" ]; then force_handshake=1; fi

if [ "$force_handshake" = "1" ]; then
  : > "$TOKEN_FILE"
  : > "$COOKIE"
  for _ in $(seq 1 60); do
    TOKEN=$(grep -oE 'token=[A-Za-z0-9_-]+' "$SRVLOG" 2>/dev/null | head -1 | cut -d= -f2)
    [ -n "$TOKEN" ] && break
    sleep 1
  done
  if [ -n "$TOKEN" ]; then
    echo "$TOKEN" > "$TOKEN_FILE"
    curl -s -o /dev/null "$BASE/?token=$TOKEN" -c "$COOKIE"
  fi
elif [ -s "$TOKEN_FILE" ] && [ -s "$COOKIE" ]; then
  TOKEN="$(cat "$TOKEN_FILE")"
else
  for _ in $(seq 1 60); do
    TOKEN=$(grep -oE 'token=[A-Za-z0-9_-]+' "$SRVLOG" 2>/dev/null | head -1 | cut -d= -f2)
    [ -n "$TOKEN" ] && break
    sleep 1
  done
  if [ -n "$TOKEN" ]; then
    echo "$TOKEN" > "$TOKEN_FILE"
    : > "$COOKIE"
    curl -s -o /dev/null "$BASE/?token=$TOKEN" -c "$COOKIE"
  fi
fi
if [ -z "$TOKEN" ] || [ ! -s "$COOKIE" ]; then
  echo "[web-e2e] [FAIL] 无法获取认证令牌/cookie（端口 $PORT 可能被外部服务占用且无 token 日志）"
  exit 1
fi

# 等健康检查（带 cookie）返回 ok
for _ in $(seq 1 30); do
  if curl -s -b "$COOKIE" "$BASE/api/agent/health" | grep -q '"status":"ok"'; then break; fi
  sleep 1
done

pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1 — $2"; FAIL=$((FAIL+1)); }

# 1) 健康检查
if curl -s -b "$COOKIE" "$BASE/api/agent/health" | grep -q '"status":"ok"'; then
  pass "GET /api/agent/health"
else
  fail "GET /api/agent/health" "未返回 ok"
fi

# 2) 一次性对话（对瞬时模型失败重试 2 次）
SEND=""
for _ in 1 2 3; do
  SEND=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
    -H 'Content-Type: application/json' \
    -d '{"message":"你好，请用一句话介绍你自己。"}' || true)
  if echo "$SEND" | jq -e '.reply and (.reply|length>0)' >/dev/null 2>&1; then break; fi
  sleep 2
done
if echo "$SEND" | jq -e '.reply and (.reply|length>0)' >/dev/null 2>&1; then
  pass "POST /api/agent/send"
  echo "    回复: $(echo "$SEND" | jq -r '.reply' | head -c 120)…"
else
  fail "POST /api/agent/send" "回复为空或异常: $(echo "$SEND" | head -c 200)"
fi

# 3) SSE 流式对话
STREAM=""
for _ in 1 2 3; do
  STREAM=$(curl -sN -b "$COOKIE" -X POST "$BASE/api/agent/stream" \
    -H 'Content-Type: application/json' \
    -d '{"message":"再说一句话。"}' 2>/dev/null || true)
  echo "$STREAM" | grep -q 'event:session' \
    && echo "$STREAM" | grep -q 'event:delta' \
    && echo "$STREAM" | grep -q 'event:done' \
    && echo "$STREAM" | grep -q '\[DONE\]' && break
  sleep 2
done
if echo "$STREAM" | grep -q 'event:session' \
   && echo "$STREAM" | grep -q 'event:delta' \
   && echo "$STREAM" | grep -q 'event:done' \
   && echo "$STREAM" | grep -q '\[DONE\]'; then
  pass "POST /api/agent/stream (SSE: session→delta*→done)"
  deltas=$(echo "$STREAM" | grep -c 'event:delta' || true)
  echo "    收到 $deltas 个 delta 帧"
else
  fail "POST /api/agent/stream" "SSE 帧不完整: $(echo "$STREAM" | head -c 200)"
fi

# ---- 以下用例与 RpcE2e 对齐：补齐 web 端缺失的会话/记忆/技能/fork/cancel 覆盖 ----

# 4) 一次性对话：完整响应（reply + totalTokens + history 齐全）
SEND2=""
for _ in 1 2 3; do
  SEND2=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
    -H 'Content-Type: application/json' \
    -d '{"message":"Reply with just the word PONG."}' || true)
  echo "$SEND2" | jq -e '.reply and (.totalTokens != null) and (.history|type=="array")' >/dev/null 2>&1 && break
  sleep 2
done
if echo "$SEND2" | jq -e '.reply and (.totalTokens != null) and (.history|type=="array")' >/dev/null 2>&1; then
  pass "POST /api/agent/send (full response: reply+tokens+history)"
else
  fail "POST /api/agent/send (full response)" "缺 totalTokens/history: $(echo "$SEND2" | head -c 200)"
fi

# 4b) appid/userid 从 header 注入（响应回显）—— SessionLog 支持 head 取 appid/userid
AUTH=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -H 'X-DSH-APPID: acme-bot' -H 'X-DSH-USERID: user42' \
  -H 'X-DSH-REASONING: true' -H 'X-DSH-MODEL: qwen3.7-max' \
  -d '{"message":"Reply with just OK."}' || true)
if echo "$AUTH" | jq -e '.appid == "acme-bot" and .userid == "user42" and .reasoning == "true" and .modelId == "qwen3.7-max"' >/dev/null 2>&1; then
  pass "appid/userid/reasoning/modelId from header (echoed)"
else
  fail "headers from header" "未回显: $(echo "$AUTH" | jq -c '{appid:.appid,userid:.userid,reasoning:.reasoning,modelId:.modelId}' 2>/dev/null)"
fi

# 4c) 全部 header 缺省走默认值（appid=default, userid="", reasoning=auto, modelId=""）
NOAUTH=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Reply with just OK."}' || true)
if echo "$NOAUTH" | jq -e '.appid == "default" and (.userid // "") == "" and .reasoning == "auto" and (.modelId // "") == ""' >/dev/null 2>&1; then
  pass "all headers default when absent"
else
  fail "headers default" "默认值不符: $(echo "$NOAUTH" | jq -c '{appid:.appid,userid:.userid,reasoning:.reasoning,modelId:.modelId}' 2>/dev/null)"
fi

# 4d) token 输入/输出量（inputTokens/outputTokens > 0）
TOK=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Introduce Python in one sentence."}' || true)
if echo "$TOK" | jq -e '.inputTokens > 0 and .outputTokens > 0' >/dev/null 2>&1; then
  pass "token input/output tracked (in/out > 0)"
  echo "    in=$(echo "$TOK" | jq -r '.inputTokens') out=$(echo "$TOK" | jq -r '.outputTokens')"
else
  fail "token input/output" "in/out 未正: $(echo "$TOK" | jq -c '{in:.inputTokens,out:.outputTokens}' 2>/dev/null)"
fi

# 5) 上下文记忆：多轮（同 sessionId 记住→回忆）—— 对齐 RPC context memory
MEM=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Please remember: my name is Alice and my web code is WEB999."}' || true)
MEM_SID=$(echo "$MEM" | jq -r '.sessionId // empty')
RECALL=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$MEM_SID\",\"message\":\"What is my name and my web code?\"}" || true)
if [ -n "$MEM_SID" ] && echo "$RECALL" | tr '[:upper:]' '[:lower:]' | grep -q "alice" \
   && echo "$RECALL" | tr '[:upper:]' '[:lower:]' | grep -q "web999"; then
  pass "context memory (multi-turn recall via same sessionId)"
else
  fail "context memory (multi-turn)" "未回忆出 alice/web999: $(echo "$RECALL" | jq -r '.reply // empty' | head -c 120)"
fi

# 6) session.list（含上述会话）—— 对齐 RPC session list
SL=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.list" \
  -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{}}' || true)
if echo "$SL" | jq -e '.result.value.items | length > 0' >/dev/null 2>&1 \
   && echo "$SL" | jq -r '.result.value.items[].sessionId' | grep -q "^$MEM_SID$"; then
  pass "POST /api/session.list (contains active session)"
else
  fail "POST /api/session.list" "未列出会话或缺少 $MEM_SID"
fi

# 7) skill.list（含已注册技能）—— 对齐 RPC skill discovery
SK=$(curl -s -b "$COOKIE" -X POST "$BASE/api/skill.list" \
  -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{}}' || true)
if echo "$SK" | jq -r '.result.value.skills[].name' 2>/dev/null | grep -q "^code-review$"; then
  pass "POST /api/skill.list (contains code-review)"
else
  fail "POST /api/skill.list" "未列出 code-review: $(echo "$SK" | head -c 200)"
fi

# 8) session.fork：子会话继承父会话记忆 —— 对齐 RPC fork child inherits parent memory
FRK=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.fork" \
  -H 'Content-Type: application/json' \
  -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$MEM_SID\"}}" || true)
CHILD_SID=$(echo "$FRK" | jq -r '.result.value.sessionId // empty')
CHILD_RECALL=""
for _ in 1 2 3; do
  CHILD_RECALL=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"$CHILD_SID\",\"message\":\"What is my name and my web code?\"}" || true)
  echo "$CHILD_RECALL" | tr '[:upper:]' '[:lower:]' | grep -q "web999" && break
  sleep 2
done
if [ -n "$CHILD_SID" ] && echo "$CHILD_RECALL" | tr '[:upper:]' '[:lower:]' | grep -q "alice" \
   && echo "$CHILD_RECALL" | tr '[:upper:]' '[:lower:]' | grep -q "web999"; then
  pass "POST /api/session.fork (child inherits parent memory)"
else
  fail "POST /api/session.fork" "子会话未回忆出 alice/web999: $(echo "$CHILD_RECALL" | jq -r '.reply // empty' | head -c 120)"
fi

# 9) 新会话无对话记忆（不继承上个会话的对话专属事实）—— 对齐 RPC new session isolation
FRESH=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is my name and my web code?"}' || true)
if ! echo "$FRESH" | tr '[:upper:]' '[:lower:]' | grep -q "web999"; then
  pass "new session has no conversation memory (no WEB999)"
else
  fail "new session isolation" "新会话不应知道上个会话对话专属事实 web999"
fi

# 10) 对话专属事实（仅对话不落盘，同 session 内存储）—— 对齐 RPC conversation-only fact (store)
CONVO_SID=$(echo "$MEM" | jq -r '.sessionId // empty')
CONVO_STORE=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$CONVO_SID\",\"message\":\"Just for THIS conversation, my one-time passphrase is CONVO777. Do NOT write it to any file - keep it only in chat context.\"}" || true)
if echo "$CONVO_STORE" | tr '[:upper:]' '[:lower:]' | grep -q "convo777"; then
  pass "conversation-only fact (store in-session)"
else
  fail "conversation-only fact (store)" "未确认存储: $(echo "$CONVO_STORE" | jq -r '.reply // empty' | head -c 100)"
fi

# 11) 回忆对话专属事实（同 session 内可回忆）—— 对齐 RPC recall conversation-only fact
CONVO_RECALL=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$CONVO_SID\",\"message\":\"What is my one-time passphrase?\"}" || true)
if echo "$CONVO_RECALL" | tr '[:upper:]' '[:lower:]' | grep -q "convo777"; then
  pass "conversation-only fact (recall in-session)"
else
  fail "conversation-only fact (recall)" "未回忆出 convo777: $(echo "$CONVO_RECALL" | jq -r '.reply // empty' | head -c 100)"
fi

# 11) query by sessionId（不同会话历史隔离）—— 对齐 RPC query by sessionId
SID1=""; SID2=""
for _ in 1 2 3; do
  D1=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
    -H 'Content-Type: application/json' -d '{"message":"Remember: my city is Tokyo."}' || true)
  SID1=$(echo "$D1" | jq -r '.sessionId // empty')
  [ -n "$SID1" ] && break; sleep 2
done
for _ in 1 2 3; do
  D2=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" \
    -H 'Content-Type: application/json' -d '{"message":"Remember: my city is Paris."}' || true)
  SID2=$(echo "$D2" | jq -r '.sessionId // empty')
  [ -n "$SID2" ] && break; sleep 2
done
H1=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.history" \
  -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$SID1\"}}" 2>/dev/null || true)
H2=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.history" \
  -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$SID2\"}}" 2>/dev/null || true)
if echo "$H1" | tr '[:upper:]' '[:lower:]' | grep -q "tokyo" \
   && echo "$H2" | tr '[:upper:]' '[:lower:]' | grep -q "paris"; then
  pass "query by sessionId (distinct histories)"
else
  fail "query by sessionId" "历史未隔离: sid1=$(echo "$H1"|head -c 80) sid2=$(echo "$H2"|head -c 80)"
fi

# 12) host.describe（provider/model）—— 对齐 RPC initialize
HD=$(curl -s -b "$COOKIE" -X POST "$BASE/api/host.describe" \
  -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{}}' 2>/dev/null || true)
if echo "$HD" | jq -e '.result.value.model // .result.value.providers' >/dev/null 2>&1; then
  pass "POST /api/host.describe (provider/model non-empty)"
else
  fail "POST /api/host.describe" "未返回 provider/model: $(echo "$HD" | head -c 200)"
fi

# 13) skill.get（加载渲染技能）—— 对齐 RPC skill load (skill/get)
SG=$(curl -s -b "$COOKIE" -X POST "$BASE/api/skill.get" \
  -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{"name":"code-review"}}' 2>/dev/null || true)
if echo "$SG" | jq -e '.result.value.found == true' >/dev/null 2>&1 \
   && echo "$SG" | grep -q 'skill_content'; then
  pass "POST /api/skill.get (code-review found + rendered)"
else
  fail "POST /api/skill.get" "未渲染 code-review: $(echo "$SG" | head -c 200)"
fi

# 14) session.compact（上下文压缩）—— 对齐 RPC context compaction
SC=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.compact" \
  -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$MEM_SID\",\"maxTokens\":2048}}" 2>/dev/null || true)
if echo "$SC" | jq -e '.result.value.before != null and .result.value.after != null' >/dev/null 2>&1; then
  pass "POST /api/session.compact (before→after)"
else
  fail "POST /api/session.compact" "未返回 before/after: $(echo "$SC" | head -c 200)"
fi

# 14b) 多轮对话累积 token → 客户端读 sessionTokens → 手动压缩（验证累积 + 压缩后缩减）
MC_SID=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -d '{"message":"Remember: my name is Bob."}' 2>/dev/null | jq -r '.sessionId // empty')
T1=0; T2=0; T3=0
for _ in 1 2 3; do [ -n "$MC_SID" ] && break; sleep 1; done
[ -n "$MC_SID" ] && T1=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$MC_SID\",\"message\":\"What is 2+2? Answer with just the number.\"}" 2>/dev/null | jq -r '.sessionTokens // 0')
[ -n "$MC_SID" ] && T2=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$MC_SID\",\"message\":\"What is 3+3? Answer with just the number.\"}" 2>/dev/null | jq -r '.sessionTokens // 0')
[ -n "$MC_SID" ] && T3=$(curl -s -b "$COOKIE" -X POST "$BASE/api/agent/send" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$MC_SID\",\"message\":\"What is 4+4? Answer with just the number.\"}" 2>/dev/null | jq -r '.sessionTokens // 0')
MC_BEFORE=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.compact" -H 'Content-Type: application/json' \
  -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$MC_SID\",\"maxTokens\":256}}" 2>/dev/null | jq -r '.result.value.before // 0')
MC_AFTER=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.compact" -H 'Content-Type: application/json' \
  -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$MC_SID\",\"maxTokens\":256}}" 2>/dev/null | jq -r '.result.value.after // 0')
if [ -n "$MC_SID" ] && [ "$T3" -gt 0 ] 2>/dev/null \
   && [ "$T3" -ge "$T1" ] 2>/dev/null \
   && [ "$MC_AFTER" -le "$MC_BEFORE" ] 2>/dev/null; then
  pass "manual compaction via sessionTokens (multi-turn accumulate→compact)"
  echo "    sessionTokens: t1=$T1 t3=$T3 | compact before=$MC_BEFORE after=$MC_AFTER"
else
  fail "manual compaction (multi-turn)" "累积/压缩不符: t1=$T1 t3=$T3 compact before=$MC_BEFORE after=$MC_AFTER"
fi

# 15) session.delete（创建+删除+验证消失）—— 对齐 RPC session deletion
DEL_SID=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.create" \
  -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{}}' 2>/dev/null | jq -r '.result.value.sessionId // empty')
DEL1=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.delete" \
  -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$DEL_SID\"}}" 2>/dev/null || true)
DEL2=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.delete" \
  -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$DEL_SID\"}}" 2>/dev/null || true)
if echo "$DEL1" | jq -e '.result.value.deleted == true' >/dev/null 2>&1 \
   && echo "$DEL2" | jq -e '.result.value.deleted == false' >/dev/null 2>&1; then
  pass "POST /api/session.delete (first true, second false)"
else
  fail "POST /api/session.delete" "删除语义不符: $(echo "$DEL1"|head -c 80) / $(echo "$DEL2"|head -c 80)"
fi

# 16) subagent.task（委派子任务）—— 对齐 RPC subagent delegation
SUB_SID=$(echo "$MEM" | jq -r '.sessionId // empty')
SUB_OK=0
for _ in 1 2; do
  SUB=$(curl -s -b "$COOKIE" -X POST "$BASE/api/subagent.task" \
    -H 'Content-Type: application/json' \
    -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$SUB_SID\",\"task\":\"Summarize the ReAct pattern in one sentence.\"}}" 2>/dev/null || true)
  echo "$SUB" | jq -e '.result.value.success == true' >/dev/null 2>&1 && SUB_OK=1 && break
  sleep 2
done
if [ "$SUB_OK" = "1" ]; then
  pass "POST /api/subagent.task (delegation success)"
else
  fail "POST /api/subagent.task" "委派未成功: $(echo "$SUB" | head -c 200)"
fi

# 17) team.run（多 agent 并行编排）—— 对齐 RPC multi-agent team
TEAM_OK=0
for _ in 1 2; do
  TM=$(curl -s -b "$COOKIE" -X POST "$BASE/api/team.run" \
    -H 'Content-Type: application/json' \
    -d '{"rpcId":"w","payload":{"task":"Explain the value of unit testing in one sentence."}}' 2>/dev/null || true)
  echo "$TM" | jq -e '.result.value.allSucceeded == true and .result.value.memberCount == 2' >/dev/null 2>&1 && TEAM_OK=1 && break
  sleep 2
done
if [ "$TEAM_OK" = "1" ]; then
  pass "POST /api/team.run (both members succeed)"
else
  fail "POST /api/team.run" "成员未全成功: $(echo "$TM" | head -c 200)"
fi

# 18) shutdown（协议握手）—— 对齐 RPC shutdown
SH=$(curl -s -b "$COOKIE" -X POST "$BASE/api/shutdown" \
  -H 'Content-Type: application/json' -d '{"rpcId":"w","payload":{}}' 2>/dev/null || true)
if echo "$SH" | jq -e '.result.value.status == "ok"' >/dev/null 2>&1; then
  pass "POST /api/shutdown (status ok)"
else
  fail "POST /api/shutdown" "未返回 ok: $(echo "$SH" | head -c 200)"
fi

# 19) session.cancel：取消运行中的 turn —— 对齐 RPC session cancel（lenient：接受取消或完成）
( curl -sN -b "$COOKIE" -X POST "$BASE/api/agent/stream" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Reply with the word HELLO."}' > /tmp/web-cancel-stream.out 2>/dev/null ) &
CAN_PID=$!
CAN_SID=""
for _ in $(seq 1 10); do
  CAN_SID=$(awk '/^event:session$/{getline; sub(/^data:/,""); gsub(/ /,""); print; exit}' /tmp/web-cancel-stream.out 2>/dev/null)
  [ -n "$CAN_SID" ] && break
  sleep 0.3
done
CAN_RES=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.cancel" \
  -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$CAN_SID\"}}" 2>/dev/null || true)
CAN_HIST_OK=0
if [ -n "$CAN_SID" ]; then
  CAN_HIST=$(curl -s -b "$COOKIE" -X POST "$BASE/api/session.history" \
    -H 'Content-Type: application/json' -d "{\"rpcId\":\"w\",\"payload\":{\"sessionId\":\"$CAN_SID\"}}" 2>/dev/null || true)
  echo "$CAN_HIST" | jq -e '.result.value.events' >/dev/null 2>&1 && CAN_HIST_OK=1
fi
# 等流结束（取消或自然完成，对齐 RPC lenient 语义）
for _ in $(seq 1 30); do kill -0 $CAN_PID 2>/dev/null || break; sleep 1; done
kill -9 $CAN_PID 2>/dev/null || true
if [ -n "$CAN_SID" ] && echo "$CAN_RES" | grep -q '"accepted":true' && [ "$CAN_HIST_OK" = "1" ]; then
  pass "POST /api/session.cancel (accepted + session survives)"
else
  fail "POST /api/session.cancel" "未返回 accepted 或会话不可查询 (sid=$CAN_SID)"
fi

echo
echo "[web-e2e] 结果: $PASS 通过, $FAIL 失败"
[ "$FAIL" -eq 0 ]
