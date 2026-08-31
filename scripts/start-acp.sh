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

# 不在此编译——先运行 scripts/build-backend.sh 生成 target/classes + rpc-cp.txt，再启动。
if [ ! -f "$CP_FILE" ]; then
  echo "[start-acp] 未找到 $CP_FILE：请先运行 scripts/build-backend.sh 编译后端。" >&2
  exit 1
fi

echo "[start-acp] 启动 ACP 服务端（模型取自 dataDir/model-config.json）..." >&2
exec java -Dlogback.configurationFile=logback-rpc.xml \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.acp.DshAcpServer
