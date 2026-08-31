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
STREAM=$(curl -sN -b "$COOKIE" -X POST "$BASE/api/agent/stream" \
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
