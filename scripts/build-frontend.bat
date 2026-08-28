@echo off
chcp 65001 >nul
setlocal
rem 编译前端模块（frontend/ vendored Cordis 源码 -> lib/）。
rem 用法： scripts\build-frontend.bat
pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

echo [build-frontend] 编译前端模块（frontend\）...
pushd "%ROOT%\frontend"
call pnpm install --frozen-lockfile
if errorlevel 1 ( echo [build-frontend] pnpm install 失败 & exit /b 1 )
call pnpm run build:lib
if errorlevel 1 ( echo [build-frontend] pnpm build:lib 失败 & exit /b 1 )
popd
echo [build-frontend] 完成
exit /b 0
