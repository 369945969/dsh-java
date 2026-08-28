#!/usr/bin/env bash
# 编译前端（frontend/ vendored Cordis）并烘焙 boot 后部署到后端 static：
#   1. pnpm install --frozen-lockfile — 安装 workspace 依赖
#   2. pnpm run build — 全量构建（build:lib 编译各包 lib/client.js + build:web 构建 web app dist/）
#   3. 部署 dist → static（源 resources + 运行时 target/classes）
#   4. bake-boot：apps/web 只是 shell，__DSH_BOOT__ + __ModuleLoader__ facade 由 dsh web 每次请求注入；
#      离线复用 harness 的 bootInjections/renderIndexInjections 把 boot 烘焙进 index.html，
#      并把各包 lib/client.js 复制到 static/plugins/<id>/client.js（rev 与 manifest 一致），
#      产出可由 dsh-app 静态托管的自包含前端。
# 用法： scripts/build-frontend.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIST="$ROOT/frontend/apps/web/dist"
STATIC_RES="$ROOT/dsh-app/src/main/resources/static"   # 源（mvn process-resources 会复制到 target）
STATIC_CP="$ROOT/dsh-app/target/classes/static"          # 运行时 classpath（start.sh 直接 java -cp 读取）
BAKE="$ROOT/frontend/scripts/bake-boot.cjs"

# 部署 dist → 指定 static 目录：先清旧构建产物（assets/ + 三个根文件），再复制新产物
deploy_static() {
  local dir="$1"
  mkdir -p "$dir"
  rm -rf "$dir/assets" "$dir/index.html" "$dir/favicon.svg" "$dir/manifest.webmanifest" 2>/dev/null || true
  cp -R "$WEB_DIST/." "$dir/"
}

echo "[build-frontend] 安装依赖 + 全量构建（frontend/）..."
cd "$ROOT/frontend"
pnpm install --frozen-lockfile
pnpm run build

echo "[build-frontend] 部署 dist → static（源 + 运行时 classpath）..."
[ -f "$WEB_DIST/index.html" ] || { echo "[build-frontend] 错误：$WEB_DIST/index.html 不存在，构建未产出 dist" >&2; exit 1; }
deploy_static "$STATIC_RES"
deploy_static "$STATIC_CP"

echo "[build-frontend] 烘焙 boot（注入 __DSH_BOOT__ + __ModuleLoader__ facade + 部署 plugin bundles）..."
node "$BAKE" "$STATIC_RES"
node "$BAKE" "$STATIC_CP"
echo "[build-frontend] 完成：自包含 static 已就绪（dsh-app 同源托管，无需 dsh web 注入）"
