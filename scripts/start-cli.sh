#!/usr/bin/env bash
# 启动 CLI 交互终端（REPL）—— 对应原 Harness 的 dsh 默认交互模式。
# 从 stdin 逐行读取用户输入，驱动 agent 对话，回复打印到 stdout。
# 支持 /exit 退出、/new 新会话、/tokens 查看累计用量；会话跨多轮保持记忆。
#
# 用法： scripts/start-cli.sh
# 环境变量（从仓库根 .env 自动加载，亦可手动 export）：
#   DEEPSEEK_API_KEY  模型 API Key（必填）
#   DSH_BASE_URL      OpenAI 兼容端点（如阿里云 https://dashscope.aliyuncs.com/compatible-mode/v1）
#   DSH_MODEL         模型名（如 glm-5.2）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_FILE="$ROOT/dsh-app/target/rpc-cp.txt"

# 加载 .env（若存在；不提交密钥）
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

# 首次或任意模块 pom 变更时重建 classpath（与其它启动脚本共用缓存）
need_build=0
if [ ! -f "$CP_FILE" ]; then need_build=1
elif [ -n "$(find "$ROOT/pom.xml" "$ROOT"/dsh-*/pom.xml "$ROOT"/testcase/pom.xml -newer "$CP_FILE" 2>/dev/null | head -1)" ]; then need_build=1; fi

if [ "$need_build" -eq 1 ]; then
  echo "[start-cli] 首次构建 classpath（install + build-classpath）..." >&2
  mvn -q -f "$ROOT/pom.xml" -pl dsh-app -am install -DskipTests -Dmaven.test.skip=true
  mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
fi

echo "[start-cli] 启动 CLI 交互终端: model=${DSH_MODEL:-deepseek-chat} baseUrl=${DSH_BASE_URL:-https://api.deepseek.com}" >&2
exec java -Dlogback.configurationFile=logback-cli.xml \
  -cp "$ROOT/dsh-app/target/classes:$(cat "$CP_FILE")" \
  com.deepseek.dsh.app.cli.DshRepl
