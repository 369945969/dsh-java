#!/usr/bin/env bash
# 运行所有 E2E 测试用例（TS）—— 启动后端（若未运行）+ 执行 node testcase/e2e.ts + 报告通过情况。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8765}"

echo "[run-e2e] 检查后端 ${PORT}..."
if ! curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/api/agent/health" 2>/dev/null | grep -q 200; then
  echo "[run-e2e] 后端未运行，启动中..."
  if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
  nohup bash "$ROOT/scripts/start.sh" "$PORT" > /tmp/dsh-e2e.log 2>&1 &
  for i in $(seq 1 60); do
    curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/api/agent/health" 2>/dev/null | grep -q 200 && break
    sleep 2
  done
  if ! curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/api/agent/health" 2>/dev/null | grep -q 200; then
    echo "[run-e2e] 后端启动失败！查看 /tmp/dsh-e2e.log"
    exit 1
  fi
  echo "[run-e2e] 后端就绪"
else
  echo "[run-e2e] 后端已在运行"
fi

echo "[run-e2e] 运行 TS 测试 (node testcase/e2e.ts)..."
echo ""
node "$ROOT/testcase/e2e.ts"
EXIT=$?
echo ""
if [ $EXIT -eq 0 ]; then
  echo "[run-e2e] ✓ 全部测试通过"
else
  echo "[run-e2e] ✗ 有测试未通过"
fi
exit $EXIT
