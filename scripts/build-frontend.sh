#!/usr/bin/env bash
# 编译前端（frontend/ vendored Cordis）：
#   1. pnpm install --frozen-lockfile — 安装 workspace 依赖
#   2. pnpm run build — 全量构建（build:lib 编译各包 lib/ + build:web 构建 web app dist/）
#   3. build:web 的 postbuild 自动把 apps/web/dist/ 复制到 dsh-app/src/main/resources/static/
# 产物：packages/*/lib/（客户端模块）+ apps/web/dist/（web bootstrap）→ dsh-app/static（后端同源托管）
# 用法： scripts/build-frontend.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[build-frontend] 安装依赖 + 全量构建（frontend/）..."
cd "$ROOT/frontend"
pnpm install --frozen-lockfile
pnpm run build
echo "[build-frontend] 完成：lib/ + dist/ 已构建，dist/ 已复制到 dsh-app/src/main/resources/static/"
