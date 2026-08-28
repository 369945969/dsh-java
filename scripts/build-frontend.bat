@echo off
chcp 65001 >nul
setlocal
rem 编译前端（frontend/ vendored Cordis）：
rem   1. pnpm install --frozen-lockfile — 安装 workspace 依赖
rem   2. pnpm run build — 全量构建（build:lib 编译各包 lib/ + build:web 构建 web app dist/）
rem   3. build:web 的 postbuild 自动把 apps/web/dist/ 复制到 dsh-app/src/main/resources/static/
rem 产物：packages\*\lib\（客户端模块）+ apps\web\dist\（web bootstrap）→ dsh-app\static（后端同源托管）
rem 用法： scripts\build-frontend.bat
pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

echo [build-frontend] 安装依赖 + 全量构建（frontend\）...
pushd "%ROOT%\frontend"
call pnpm install --frozen-lockfile
if errorlevel 1 ( echo [build-frontend] pnpm install 失败 & exit /b 1 )
call pnpm run build
if errorlevel 1 ( echo [build-frontend] pnpm build 失败 & exit /b 1 )
popd
echo [build-frontend] 完成：lib/ + dist/ 已构建，dist/ 已复制到 dsh-app\src\main\resources\static\
exit /b 0
