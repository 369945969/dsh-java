#!/usr/bin/env bash
# 开发模式：启动后端 Web 服务（Spring Boot，8765），同源托管原版 Cordis 前端 shell + apiproxy 网关。
# 原版前端静态资源已构建提交于 dsh-app/src/main/resources/static，由后端托管，
# 浏览器打开 http://localhost:8765 即可对话后端 agent（原版 UI + Java apiproxy）。
#
# 若需热改原版前端源码（frontend/ 树），单独在 frontend/ 跑 `pnpm dsh web`（原版 harness 自带 dev），
# 重建后把 dist + 启动快照覆盖回 dsh-app/src/main/resources/static。
#
# 用法： scripts/dev.sh
# 环境变量同 start-rpc.sh（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi

echo "[dev] 启动后端 Web 服务（托管原版前端 + apiproxy）: http://localhost:8765" >&2
exec "$ROOT/scripts/start-web.sh" 8765
