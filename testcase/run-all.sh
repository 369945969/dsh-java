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
# 模型不再从环境变量读取：后端/RPC 服务端直接加载 dataDir/model-config.json
# （网页「添加自定义模型」保存的活跃档案）—— 与 start-cli.sh 相同的模型获取方式。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "============================================================"
echo "[run-all] 端到端验证  模型取自 ~/.dsh/model-config.json（网页保存的活跃档案）"
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
AUTH_DIR="$ROOT/testcase/.auth"
SRVLOG="$AUTH_DIR/server.log"
TOKEN_FILE="$AUTH_DIR/token.txt"
COOKIE="$AUTH_DIR/cookie.jar"
mkdir -p "$AUTH_DIR"
echo
echo "[run-all] 3/4 启动 Web 服务端（REST + SSE + WebSocket）..."
: > "$SRVLOG"
"$ROOT/scripts/start.sh" 8765 >>"$SRVLOG" 2>&1 &
WEB_PID=$!
cleanup_web() { [ -n "${WEB_PID:-}" ] && kill "$WEB_PID" 2>/dev/null || true; }
trap 'cleanup_web' EXIT

# 解析启动令牌并换 cookie（与 web-e2e.sh 同款握手）；web-e2e.sh 复用本实例
TOKEN=""
for _ in $(seq 1 60); do
  TOKEN=$(grep -oE 'token=[A-Za-z0-9_-]+' "$SRVLOG" 2>/dev/null | head -1 | cut -d= -f2)
  [ -n "$TOKEN" ] && break
  sleep 1
done
if [ -z "$TOKEN" ]; then
  echo "[run-all] [FAIL] 无法获取启动令牌（见 $SRVLOG）"; skill_ok=1; web_ok=1
else
  echo "$TOKEN" > "$TOKEN_FILE"
  : > "$COOKIE"
  curl -s -o /dev/null "http://localhost:8765/?token=$TOKEN" -c "$COOKIE"
  for _ in $(seq 1 30); do
    if curl -s -b "$COOKIE" http://localhost:8765/api/agent/health | grep -q '"status":"ok"'; then break; fi
    sleep 1
  done
fi

# skill.list（apiproxy 接真实 SkillRegistry；应返回有效技能列表）
echo
echo "[run-all] skill.list 验证（apiproxy 返回技能列表）..."
skill_ok=0
SKILLS=$(curl -s -b "$COOKIE" -X POST http://localhost:8765/api/skill.list \
  -H 'Content-Type: application/json' -d '{"rpcId":"e2e","payload":{}}' || true)
echo "$SKILLS" | grep -q '"count"' || skill_ok=1

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

# 4c) 前端真实交互 E2E（SPA 渲染→输入→发送→回复渲染，需 playwright + chromium）
echo
echo "[run-all] 4c/4 前端交互 E2E（chromium 驱动 SPA）..."
fe_ok=0
if python3 -c "import playwright" 2>/dev/null && [ -x /usr/bin/chromium-browser ]; then
  python3 "$ROOT/testcase/frontend-e2e.py" || fe_ok=1
else
  echo "  [SKIP] 未安装 playwright 或 chromium-browser（前端交互 E2E 跳过）"
  fe_ok=0
fi

cleanup_web
echo
echo "============================================================"
echo "[run-all] 总结: RPC=$([ $rpc_ok -eq 0 ] && echo PASS || echo FAIL)  Web=$([ $web_ok -eq 0 ] && echo PASS || echo FAIL)  WebSocket=$([ $ws_ok -eq 0 ] && echo PASS || echo FAIL)  Frontend=$([ $fe_ok -eq 0 ] && echo PASS || echo FAIL)  Skill=$([ $skill_ok -eq 0 ] && echo PASS || echo FAIL)"
echo "============================================================"
[ $rpc_ok -eq 0 ] && [ $web_ok -eq 0 ] && [ $ws_ok -eq 0 ] && [ $fe_ok -eq 0 ] && [ $skill_ok -eq 0 ]
