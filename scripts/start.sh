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

# Set a fixed launch token so it stays the same across restarts.
# Override by setting DSH_TOKEN env var before running this script.
if [ -z "${DSH_TOKEN:-}" ]; then
  export DSH_TOKEN="ECkvAL8rG-BYj_ex_B8hleaq8mk88ncheFEor1SoDkg"
fi
echo "[start] launch token: $DSH_TOKEN" >&2

# 不在此编译——先运行 scripts/build-backend.sh 生成 target/classes + rpc-cp.txt，再启动。
if [ ! -f "$CP_FILE" ]; then
  echo "[start] 未找到 $CP_FILE：请先运行 scripts/build-backend.sh 编译后端。" >&2
  exit 1
fi

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

echo "[start] launching web server: port=$PORT" >&2
free_port "$PORT"

# DSH_STORAGE=mysql 时启动前引导 DB（非破坏：CREATE IF NOT EXISTS + seed，不 DROP）
if [ "${DSH_STORAGE:-file}" = "mysql" ]; then
  echo "[start] DSH_STORAGE=mysql，引导 DB（非破坏）..." >&2
  MYSQL_DB="${DSH_DB_NAME:-dsh-java}"
  mysql -u "${DSH_DB_USER:-root}" -p"${DSH_DB_PASSWORD:?DSH_DB_PASSWORD required when DSH_STORAGE=mysql}" \
    "$MYSQL_DB" < "$ROOT/db/mysql/bootstrap.sql" 2>&1 | grep -vE '^\s*$' >&2 || true
  # 模型档案：若 DB 无 default 档案，从 model-config.json 导入（首次引导）
  CNT=$(mysql -u "${DSH_DB_USER:-root}" -p"${DSH_DB_PASSWORD}" -N -e "SELECT COUNT(*) FROM model_profile WHERE appid='default'" "$MYSQL_DB" 2>/dev/null || echo 0)
  if [ "${CNT:-0}" = "0" ]; then
    echo "[start] DB 无模型档案，从 model-config.json 导入..." >&2
    DSH_DB="$MYSQL_DB" MYSQL_PWD="$DSH_DB_PASSWORD" bash "$ROOT/db/mysql/load-model-profiles.sh" >&2 || true
  fi
fi

SRVLOG="$ROOT/testcase/.auth/server.log"
mkdir -p "$(dirname "$SRVLOG")"
: > "$SRVLOG"

# Launch in background (non-blocking): java output goes to server.log
nohup java -Dserver.port="$PORT" \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.boot.DshApplication >> "$SRVLOG" 2>&1 &

echo "[start] web server started in background (port=$PORT)"
echo "[start] URL: http://localhost:$PORT/?token=$DSH_TOKEN"
