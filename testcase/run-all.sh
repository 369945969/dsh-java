#!/usr/bin/env bash
# 一键端到端验证 —— 按开发模式分组覆盖全部场景：
#
#   【基础对话模式】RPC: 基础问候 / 完整返回响应 / 自定义模型调用
#   【会话与记忆模式】RPC: 记忆保存 / fork保留记忆 / fork新session无记忆 /
#                         查询session列表+单session状态 / 删除管理 / 上下文压缩
#   【技能与编排模式】RPC: 技能发现(list/get) / subagent委派 / 多agent并行编排(team)
#   【实时通信模式】Web: SSE流响应 / 完整返回(send) ; WebSocket: 并发多session+流式+取消
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

# 临时数据目录（hermetic：会话/技能隔离，不污染 ~/.dsh）
export DSH_DATA_DIR="$(mktemp -d -t dsh-e2e-XXXXXX)"
trap 'rm -rf "$DSH_DATA_DIR"' EXIT

echo "============================================================"
echo "[run-all] 端到端验证  model=${DSH_MODEL:-deepseek-chat}  baseUrl=${DSH_BASE_URL:-https://api.deepseek.com}"
echo "          临时数据目录: $DSH_DATA_DIR"
echo "============================================================"

# 1) 构建（install 刷新本地仓库 jar，确保 exec:java 与服务端 cp.txt 用到最新 dsh-sdk/dsh-web）
echo
echo "[run-all] 1/4 构建后端 + testcase 驱动（install）..."
mvn -q install -DskipTests -Dmaven.test.skip=true

export DSH_RPC_CMD="$ROOT/scripts/start-rpc.sh"

# 2) RPC E2E（基础对话 / 会话与记忆 / 技能与编排）
echo
echo "[run-all] 2/4 RPC E2E —— 基础对话 + 会话与记忆 + 技能与编排（stdio JSON-RPC，基于 dsh SDK 客户端）..."
rpc_ok=0
mvn -q -pl testcase exec:java -Dexec.mainClass=com.deepseek.dsh.testcase.RpcE2e || rpc_ok=1

# 3) Web 服务端（一个实例供 SSE + WebSocket 共用）
echo
echo "[run-all] 3/4 启动 Web 服务端（REST + SSE + WebSocket）..."
"$ROOT/scripts/start-web.sh" 8765 >&2 &
WEB_PID=$!
cleanup_web() { [ -n "${WEB_PID:-}" ] && kill "$WEB_PID" 2>/dev/null || true; }
trap 'cleanup_web; rm -rf "$DSH_DATA_DIR"' EXIT
for _ in $(seq 1 60); do
  if curl -sf http://localhost:8765/api/agent/health >/dev/null 2>&1; then break; fi
  sleep 1
done

# 4a) Web SSE/HTTP E2E（流响应 / 完整返回）
echo
echo "[run-all] 4a/4 Web SSE/HTTP E2E（流响应 + 完整返回，模拟前端）..."
web_ok=0
"$ROOT/testcase/web-e2e.sh" 8765 || web_ok=1

# 4b) WebSocket E2E（并发多 session + 流式 + 取消）
echo
echo "[run-all] 4b/4 WebSocket E2E（并发多 session + 流式 + 取消）..."
ws_ok=0
python3 "$ROOT/testcase/ws-e2e.py" || ws_ok=1

cleanup_web
echo
echo "============================================================"
echo "[run-all] 总结: RPC=$([ $rpc_ok -eq 0 ] && echo PASS || echo FAIL)  Web=$([ $web_ok -eq 0 ] && echo PASS || echo FAIL)  WebSocket=$([ $ws_ok -eq 0 ] && echo PASS || echo FAIL)"
echo "============================================================"
[ $rpc_ok -eq 0 ] && [ $web_ok -eq 0 ] && [ $ws_ok -eq 0 ]
