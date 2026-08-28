@echo off
chcp 65001 >nul
setlocal
rem 编译前端（frontend/ vendored Cordis）并烘焙 boot 后部署到后端 static：
rem   1. pnpm install --frozen-lockfile — 安装 workspace 依赖
rem   2. pnpm run build — 全量构建（build:lib 编译各包 lib/client.js + build:web 构建 web app dist/）
rem   3. 部署 dist → static（源 resources + 运行时 target/classes）
rem   4. bake-boot：apps/web 只是 shell，__DSH_BOOT__ + __ModuleLoader__ facade 由 dsh web 每次请求注入；
rem      离线复用 harness 的 bootInjections/renderIndexInjections 把 boot 烘焙进 index.html，
rem      并把各包 lib/client.js 复制到 static/plugins/<id>/client.js（rev 与 manifest 一致），
rem      产出可由 dsh-app 静态托管的自包含前端。
rem 用法： scripts\build-frontend.bat
pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul
set "WEB_DIST=%ROOT%\frontend\apps\web\dist"
set "STATIC_RES=%ROOT%\dsh-app\src\main\resources\static"
set "STATIC_CP=%ROOT%\dsh-app\target\classes\static"
set "BAKE=%ROOT%\frontend\scripts\bake-boot.cjs"

echo [build-frontend] 安装依赖 + 全量构建（frontend\）...
pushd "%ROOT%\frontend"
call pnpm install --frozen-lockfile
if errorlevel 1 ( echo [build-frontend] pnpm install 失败 & exit /b 1 )
call pnpm run build
if errorlevel 1 ( echo [build-frontend] pnpm build 失败 & exit /b 1 )
popd

if not exist "%WEB_DIST%\index.html" (
  echo [build-frontend] 错误：%WEB_DIST%\index.html 不存在，构建未产出 dist
  exit /b 1
)

echo [build-frontend] 部署 dist -^> static（源 + 运行时 classpath）...

rem 部署到源 static
if exist "%STATIC_RES%\assets" rmdir /s /q "%STATIC_RES%\assets"
for %%F in ("%STATIC_RES%\index.html" "%STATIC_RES%\favicon.svg" "%STATIC_RES%\manifest.webmanifest") do if exist "%%~F" del /q "%%~F"
xcopy "%WEB_DIST%\*" "%STATIC_RES%\" /E /Y /I /Q >nul
if errorlevel 1 ( echo [build-frontend] 部署到源 static 失败 & exit /b 1 )

rem 部署到运行时 classpath static（start.sh 直接 java -cp 读取，跳过 mvn 时需已更新）
if not exist "%STATIC_CP%" mkdir "%STATIC_CP%"
if exist "%STATIC_CP%\assets" rmdir /s /q "%STATIC_CP%\assets"
for %%F in ("%STATIC_CP%\index.html" "%STATIC_CP%\favicon.svg" "%STATIC_CP%\manifest.webmanifest") do if exist "%%~F" del /q "%%~F"
xcopy "%WEB_DIST%\*" "%STATIC_CP%\" /E /Y /I /Q >nul
if errorlevel 1 ( echo [build-frontend] 部署到 classpath static 失败 & exit /b 1 )

echo [build-frontend] 烘焙 boot（注入 __DSH_BOOT__ + __ModuleLoader__ facade + 部署 plugin bundles）...
call node "%BAKE%" "%STATIC_RES%"
if errorlevel 1 ( echo [build-frontend] bake-boot 源 static 失败 & exit /b 1 )
call node "%BAKE%" "%STATIC_CP%"
if errorlevel 1 ( echo [build-frontend] bake-boot classpath static 失败 & exit /b 1 )

echo [build-frontend] 完成：自包含 static 已就绪（dsh-app 同源托管，无需 dsh web 注入）
exit /b 0
