#!/usr/bin/env bash
# 一键端到端验证：构建 → RPC E2E（stdio JSON-RPC，SDK 客户端）→ Web E2E（HTTP/SSE，模拟前端）。
#
# 验证后端提供的两种访问协议的全部功能：
#   - RPC：initialize / health / session.create / session.list / session/prompt / session.history / session.delete / shutdown
#   - SSE：GET /api/agent/health、POST /api/agent/send、POST /api/agent/stream
#
# 用法： testcase/run-all.sh
# 环境变量从仓库根 .env 自动加载（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[run-all] 未配置 DEEPSEEK_API_KEY：复制 .env.example 为 .env 填入 glm-5.2 等 key 后再运行。"
  exit 1
fi

echo "============================================================"
echo "[run-all] 端到端验证  model=${DSH_MODEL:-deepseek-chat}  baseUrl=${DSH_BASE_URL:-https://api.deepseek.com}"
echo "============================================================"

# 1) 构建后端与 testcase 驱动（复用 start-rpc.sh 的 classpath 缓存）
echo
echo "[run-all] 1/3 构建后端 + testcase 驱动..."
mvn -q -pl dsh-app,testcase -am compile

export DSH_RPC_CMD="$ROOT/scripts/start-rpc.sh"

# 2) RPC E2E（SDK 客户端驱动，spawn start-rpc.sh 子进程）
echo
echo "[run-all] 2/3 RPC E2E（stdio JSON-RPC，基于 dsh SDK 客户端）..."
rpc_ok=0
mvn -q -pl testcase exec:java -Dexec.mainClass=com.deepseek.dsh.testcase.RpcE2e || rpc_ok=1

# 3) Web E2E（HTTP + SSE，模拟前端）
echo
echo "[run-all] 3/3 Web E2E（HTTP REST + SSE 流式，模拟前端）..."
web_ok=0
"$ROOT/testcase/web-e2e.sh" 8765 || web_ok=1

echo
echo "============================================================"
echo "[run-all] 总结: RPC=$([ $rpc_ok -eq 0 ] && echo PASS || echo FAIL)  Web=$([ $web_ok -eq 0 ] && echo PASS || echo FAIL)"
echo "============================================================"
[ $rpc_ok -eq 0 ] && [ $web_ok -eq 0 ]
