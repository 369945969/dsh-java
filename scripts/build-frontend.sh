#!/usr/bin/env bash
# 编译前端模块（frontend/ vendored Cordis 源码 → lib/）。
# 用法： scripts/build-frontend.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[build-frontend] 编译前端模块（frontend/）..."
cd "$ROOT/frontend"
pnpm install --frozen-lockfile
pnpm run build:lib
echo "[build-frontend] 完成"
