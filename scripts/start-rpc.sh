#!/usr/bin/env bash
# 启动 RPC 服务端（stdio newline-delimited JSON-RPC 2.0）—— 对应原 Harness 的
# dsh-jsonrpc-agent 运行时。stdout 仅承载 JSON-RPC 帧，所有日志走 stderr
# （logback-rpc.xml），保证 SDK 客户端可干净读取。
#
# 用法： scripts/start-rpc.sh
# 模型/key/端点取自 dataDir/model-config.json（网页「添加自定义模型」保存的活跃档案），
# 不再从环境变量读取。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

# 每次启动前重新编译后端（clean install 拾取任意 .java/pom 改动），再刷新 classpath
echo "[start-rpc] 重新编译后端（mvn clean install）..." >&2
mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true
mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

echo "[start-rpc] 启动 RPC 服务端（模型取自 dataDir/model-config.json）..." >&2
exec java -Dlogback.configurationFile=logback-rpc.xml \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.rpc.DshRpcServer
