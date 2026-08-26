#!/usr/bin/env bash
# 一键启动前后端：构建前端 → 同步到后端静态资源 → 启动 Web 服务。
# SPA + REST + SSE + WebSocket 同源（默认 8765），打开 http://localhost:8765 即可用前端对话后端。
#
# 用法： scripts/start.sh [port]
# 环境变量从仓库根 .env 自动加载（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

echo "[start] 1/3 构建前端（pnpm build → dist）..."
cd "$ROOT/dsh-frontend"
[ -d node_modules ] || pnpm install
pnpm build

echo "[start] 2/3 同步前端到后端静态资源（src/main/resources/static）..."
STATIC="$ROOT/dsh-app/src/main/resources/static"
rm -rf "$STATIC"
mkdir -p "$STATIC/assets"
cp dist/index.html "$STATIC/index.html"
cp -r dist/assets/* "$STATIC/assets/"
# 刷新 target/classes/static，使 Web 服务立即提供最新前端
mvn -q -f "$ROOT/pom.xml" -pl dsh-app process-resources

echo "[start] 3/3 启动 Web 服务（SPA + API 同源，port=$PORT）..."
exec "$ROOT/scripts/start-web.sh" "$PORT"
