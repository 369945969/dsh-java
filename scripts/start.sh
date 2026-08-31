#!/usr/bin/env bash
# 一键启动：编译后端 → 启动 Web 服务（托管原版 Cordis 前端 shell + apiproxy 网关）。
# 前端静态资源（原版 shell + __DSH_BOOT__ 启动快照 + 42 个插件包）已构建并提交于
# dsh-app/src/main/resources/static，由后端同源托管，无需运行时重建。
# 打开 http://localhost:8765 即可用原版前端对话后端 agent。
#
# 用法： scripts/start.sh [port]
# 模型/key/端点取自 dataDir/model-config.json（网页「添加自定义模型」保存的活跃档案），
# 不再从环境变量读取。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

# 每次启动前重新编译后端（clean install 拾取任意 .java/pom 改动），再刷新 classpath
echo "[start] 重新编译后端（mvn clean install）..." >&2
mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true
mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

# 释放端口：先 SIGTERM 优雅关闭（等 ~3s 触发 Spring Boot shutdown hook），仍占用则 SIGKILL 强杀
free_port() {
  local pids i
  pids="$(lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null || true)"
  if [ -z "$pids" ]; then return 0; fi
  echo "[start] 端口 $1 被占用，结束旧进程: $(echo "$pids" | tr '\n' ' ')" >&2
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  i=0
  while [ $i -lt 10 ]; do
    pids="$(lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null || true)"
    if [ -z "$pids" ]; then return 0; fi
    sleep 0.3; i=$((i+1))
  done
  echo "[start] 旧进程未优雅退出，强制结束" >&2
  # shellcheck disable=SC2086
  kill -9 $pids 2>/dev/null || true
  sleep 0.3
}

echo "[start] 启动 Web 服务端: port=$PORT" >&2
free_port "$PORT"

# 捕获 Java 输出，提取 token URL 后打印到 stderr
java -Dserver.port="$PORT" \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.boot.DshApplication 2>&1 | while IFS= read -r line; do
  echo "$line" >&2
  if echo "$line" | grep -q "authentication URL:"; then
    echo "" >&2
    echo "================================================" >&2
    echo "$line" | sed 's/.*authentication URL: //' >&2
    echo "================================================" >&2
    echo "" >&2
  fi
done
