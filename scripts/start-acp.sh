#!/usr/bin/env bash
# 启动 ACP 服务端（Automation-only Agent Client Protocol，stdio newline-delimited
# JSON-RPC 2.0）—— 对应原 Harness 的 dsh acp 模式。stdout 仅承载 JSON-RPC 帧，
# 所有日志走 stderr（logback-rpc.xml），保证自动化客户端可干净读取。
#
# 方法面（自动化专用最小集）：session.create / session.run / session.list / shutdown
#
# 用法： scripts/start-acp.sh
# 模型/key/端点取自 dataDir/model-config.json（网页「添加自定义模型」保存的活跃档案），
# 不再从环境变量读取。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

# 每次启动前重新编译后端（clean install 拾取任意 .java/pom 改动），再刷新 classpath
echo "[start-acp] 重新编译后端（mvn clean install）..." >&2
mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true
mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

echo "[start-acp] 启动 ACP 服务端（模型取自 dataDir/model-config.json）..." >&2
exec java -Dlogback.configurationFile=logback-rpc.xml \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.acp.DshAcpServer
