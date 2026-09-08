#!/usr/bin/env bash
# 启动 agentscope WS 模式（HTTP + WebSocket 流式）
# 用法：scripts/start-ws.sh [port]
# 环境变量：同 start-web.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PORT="${1:-${DSH_PORT:-8766}}"
JAR="$ROOT/target/agentscope-1.0.0.jar"

[ -f "$JAR" ] || { echo "[start-ws] 未找到 jar，请先 scripts/build.sh"; exit 1; }
[ -n "${DSH_TOKEN:-}" ] || { echo "[start-ws] 请设 DSH_TOKEN 环境变量"; exit 1; }
[ -n "${DASHSCOPE_API_KEY:-}" ] || { echo "[start-ws] 请设 DASHSCOPE_API_KEY 环境变量"; exit 1; }

echo "[start-ws] launching web + ws (port=$PORT)..."
java -jar "$JAR" ws "$PORT"
