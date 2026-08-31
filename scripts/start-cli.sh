#!/usr/bin/env bash
# 启动 CLI 交互终端（REPL）—— 对应原 Harness 的 dsh 默认交互模式。
# 从 stdin 逐行读取用户输入，驱动 agent 对话，回复打印到 stdout。
# 支持 /exit 退出、/new 新会话、/tokens 查看累计用量；会话跨多轮保持记忆。
#
# 用法： scripts/start-cli.sh
# 模型/key/端点取自 dataDir/model-config.json（网页「添加自定义模型」保存的活跃档案），
# 不再从环境变量读取。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

# 不在此编译——先运行 scripts/build-backend.sh 生成 target/classes + rpc-cp.txt，再启动。
if [ ! -f "$CP_FILE" ]; then
  echo "[start-cli] 未找到 $CP_FILE：请先运行 scripts/build-backend.sh 编译后端。" >&2
  exit 1
fi

echo "[start-cli] 启动中..." >&2
exec java -Dlogback.configurationFile=logback-cli.xml \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.cli.DshRepl
