#!/usr/bin/env bash
# 一键启动：编译后端 → 启动 Web 服务（托管原版 Cordis 前端 shell + apiproxy 网关）。
# 前端静态资源（原版 shell + __DSH_BOOT__ 启动快照 + 42 个插件包）已构建并提交于
# dsh-app/src/main/resources/static，由后端同源托管，无需运行时重建。
# 打开 http://localhost:8765 即可用原版前端对话后端 agent。
#
# 用法： scripts/start.sh [port]
# 环境变量从仓库根 .env 自动加载（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

# 首次构建 classpath（任意模块 pom 变更即重建）
if [ ! -f "$CP_FILE" ] || [ -n "$(find "$ROOT/pom.xml" "$ROOT"/dsh-*/pom.xml "$ROOT"/testcase/pom.xml -newer "$CP_FILE" 2>/dev/null | head -1)" ]; then
  echo "[start] 首次构建 classpath..." >&2
  mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am install -DskipTests -Dmaven.test.skip=true
  mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
fi

echo "[start] 启动 Web 服务端: port=$PORT model=${DSH_MODEL:-deepseek-chat}" >&2
exec java -Dserver.port="$PORT" \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.boot.DshApplication
