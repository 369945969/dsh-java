#!/usr/bin/env bash
# 启动 Web 服务端（Spring Boot，含 REST + SSE 流式面）—— 对应原 Harness 的
# dsh web 模式。前端（自带 React 或用户自有前端）通过 HTTP/SSE 对接。
# 默认端口 8765；前端 dev 代理见 scripts/dev.sh。
#
# 用法： scripts/start-web.sh [port]
# 环境变量同 start-rpc.sh（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

# 复用 start-rpc.sh 的 classpath 构建（保证 dsh-* 已 install）
if [ ! -f "$CP_FILE" ] || [ "$ROOT/pom.xml" -nt "$CP_FILE" ]; then
  echo "[start-web] 首次构建 classpath..." >&2
  mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am install -DskipTests -Dmaven.test.skip=true
  mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
fi

echo "[start-web] 启动 Web 服务端: port=$PORT model=${DSH_MODEL:-deepseek-chat}" >&2
exec java -Dserver.port="$PORT" \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.boot.DshApplication
