#!/usr/bin/env bash
# 启动 agentscope Web 模式（HTTP REST + 静态 UI，无 WebSocket）
# 用法：scripts/start-web.sh [port]
# 环境变量：
#   DSH_TOKEN=DASHSCOPE_API_KEY=...  必填
#   DSH_MODEL=dashscope:qwen3.7-max  模型（默认）
#   DSH_PORT=8766                     端口
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PORT="${1:-${DSH_PORT:-8766}}"
JAR="$ROOT/target/agentscope-1.0.0.jar"

[ -f "$JAR" ] || { echo "[start-web] 未找到 jar，请先 scripts/build.sh"; exit 1; }
[ -n "${DSH_TOKEN:-}" ] || { echo "[start-web] 请设 DSH_TOKEN 环境变量"; exit 1; }
[ -n "${DASHSCOPE_API_KEY:-}" ] || { echo "[start-web] 请设 DASHSCOPE_API_KEY 环境变量"; exit 1; }

echo "[start-web] launching web (port=$PORT)..."
java -jar "$JAR" web "$PORT"
