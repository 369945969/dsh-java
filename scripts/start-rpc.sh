#!/usr/bin/env bash
# 启动 RPC 服务端（stdio newline-delimited JSON-RPC 2.0）—— 对应原 Harness 的
# dsh-jsonrpc-agent 运行时。stdout 仅承载 JSON-RPC 帧，所有日志走 stderr
# （logback-rpc.xml），保证 SDK 客户端可干净读取。
#
# 用法： scripts/start-rpc.sh
# 环境变量（从仓库根 .env 自动加载，亦可手动 export）：
#   DEEPSEEK_API_KEY  模型 API Key（必填）
#   DSH_BASE_URL      OpenAI 兼容端点（如阿里云 https://dashscope.aliyuncs.com/compatible-mode/v1）
#   DSH_MODEL         模型名（如 glm-5.2）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

# 加载 .env（若存在；不提交密钥）
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

# 每次启动前重新编译后端（clean install 拾取任意 .java/pom 改动），再刷新 classpath
echo "[start-rpc] 重新编译后端（mvn clean install）..." >&2
mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true
mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

echo "[start-rpc] 启动 RPC 服务端: model=${DSH_MODEL:-deepseek-chat} baseUrl=${DSH_BASE_URL:-https://api.deepseek.com}" >&2
exec java -Dlogback.configurationFile=logback-rpc.xml \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.rpc.DshRpcServer
