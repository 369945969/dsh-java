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
# 环境变量同 start-rpc.sh（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"
BASE="http://localhost:$PORT"
PASS=0; FAIL=0

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

# 仅当端口未占用时启动自带服务端
start_server=0
if ! curl -sf "$BASE/api/agent/health" >/dev/null 2>&1; then
  echo "[web-e2e] 启动 Web 服务端 (port=$PORT)..." >&2
  "$ROOT/scripts/start-web.sh" "$PORT" >&2 &
  WEB_PID=$!
  start_server=1
  trap '[ -n "${WEB_PID:-}" ] && kill "$WEB_PID" 2>/dev/null || true' EXIT INT TERM
  for _ in $(seq 1 60); do
    if curl -sf "$BASE/api/agent/health" >/dev/null 2>&1; then break; fi
    sleep 1
  done
fi

pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1 — $2"; FAIL=$((FAIL+1)); }

# 1) 健康检查
if curl -sf "$BASE/api/agent/health" | grep -q '"status":"ok"'; then
  pass "GET /api/agent/health"
else
  fail "GET /api/agent/health" "未返回 ok"
fi

# 2) 一次性对话（对瞬时模型失败重试 2 次）
SEND=""
for _ in 1 2 3; do
  SEND=$(curl -s -X POST "$BASE/api/agent/send" \
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
STREAM=$(curl -sN -X POST "$BASE/api/agent/stream" \
  -H 'Content-Type: application/json' \
  -d '{"message":"再说一句话。"}' 2>/dev/null || true)
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

echo
echo "[web-e2e] 结果: $PASS 通过, $FAIL 失败"
[ "$FAIL" -eq 0 ]
