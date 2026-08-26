#!/usr/bin/env bash
# 开发模式：后端 Web 服务端（Spring Boot，8765）+ 前端 Vite dev（5173，代理 /api 到后端）并行。
# 任一进程退出则全部退出。对应原 Harness 的前后端分离开发模式。
#
# 用法： scripts/dev.sh
# 环境变量同 start-rpc.sh（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

cleanup() {
  echo "[dev] 停止后端与前端..." >&2
  [ -n "${WEB_PID:-}" ] && kill "$WEB_PID" 2>/dev/null || true
  [ -n "${FE_PID:-}" ] && kill "$FE_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# 后端
"$ROOT/scripts/start-web.sh" 8765 &
WEB_PID=$!

# 等后端就绪
echo "[dev] 等待后端 8765 就绪..." >&2
for _ in $(seq 1 60); do
  if curl -sf http://localhost:8765/api/agent/health >/dev/null 2>&1; then break; fi
  sleep 1
done

# 前端 dev
cd "$ROOT/dsh-frontend"
if [ ! -d node_modules ]; then pnpm install; fi
pnpm dev &
FE_PID=$!

echo "[dev] 后端 http://localhost:8765 | 前端 http://localhost:5173" >&2
wait
