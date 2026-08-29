@echo off
chcp 65001 >nul
setlocal
rem Build frontend (frontend/ vendored Cordis) and bake boot, then deploy to backend static:
rem   1. pnpm install --frozen-lockfile - install workspace dependencies
rem   2. pnpm run build - full build (build:lib compiles each package lib/client.js + build:web builds web app dist/)
rem   3. deploy dist -> static (source resources + runtime target/classes)
rem   4. bake-boot: apps/web is only a shell, __DSH_BOOT__ + __ModuleLoader__ facade is injected
rem      by dsh web on each request; offline reuse of harness bootInjections/renderIndexInjections
rem      bakes boot into index.html and copies each package lib/client.js to
rem      static/plugins/<id>/client.js (rev matches manifest), producing a self-contained
rem      frontend that dsh-app can statically host.
rem Usage: scripts\build-frontend.bat
pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul
set "WEB_DIST=%ROOT%\frontend\apps\web\dist"
set "STATIC_RES=%ROOT%\dsh-app\src\main\resources\static"
set "STATIC_CP=%ROOT%\dsh-app\target\classes\static"
set "BAKE=%ROOT%\frontend\scripts\bake-boot.cjs"

rem --- environment check: Node 22.19.0+ (below 23) or 24.0.0+; older Node lacks import.meta.main,
rem     so frontend/scripts/build.ts `if (import.meta.main) main()` is a silent no-op (no dist). ---
where node >nul 2>nul
if errorlevel 1 ( echo [build-frontend] error: node not found on PATH & exit /b 1 )
for /f "delims=" %%V in ('node --version') do set "NODE_VER=%%V"
set "NODE_VER=%NODE_VER:v=%"
for /f "tokens=1,2 delims=." %%a in ("%NODE_VER%") do ( set "NODE_MAJOR=%%a" & set "NODE_MINOR=%%b" )
set "NODE_OK=0"
if %NODE_MAJOR% EQU 22 ( if %NODE_MINOR% GEQ 19 set "NODE_OK=1" )
if %NODE_MAJOR% GEQ 24 set "NODE_OK=1"
if not "%NODE_OK%"=="1" (
  echo [build-frontend] error: Node version requirement not met.
  echo [build-frontend]        required 22.19.0+ and below 23, or 24.0.0+
  echo [build-frontend]        found    v%NODE_VER%
  echo [build-frontend]        older Node lacks import.meta.main, so build.ts is a silent no-op.
  exit /b 1
)
echo [build-frontend] node v%NODE_VER% ok

echo [build-frontend] install deps + full build (frontend\)...
pushd "%ROOT%\frontend"
call pnpm install --frozen-lockfile
if errorlevel 1 ( echo [build-frontend] pnpm install failed & exit /b 1 )
call pnpm run build
if errorlevel 1 ( echo [build-frontend] pnpm build failed & exit /b 1 )
popd

if not exist "%WEB_DIST%\index.html" (
  echo [build-frontend] error: %WEB_DIST%\index.html does not exist, build produced no dist
  exit /b 1
)

echo [build-frontend] deploy dist -^> static (source + runtime classpath)...

rem deploy to source static
if exist "%STATIC_RES%\assets" rmdir /s /q "%STATIC_RES%\assets"
for %%F in ("%STATIC_RES%\index.html" "%STATIC_RES%\favicon.svg" "%STATIC_RES%\manifest.webmanifest") do if exist "%%~F" del /q "%%~F"
xcopy "%WEB_DIST%\*" "%STATIC_RES%\" /E /Y /I /Q >nul
if errorlevel 1 ( echo [build-frontend] deploy to source static failed & exit /b 1 )

rem deploy to runtime classpath static (start.sh reads it directly via java -cp, must be updated when skipping mvn)
if not exist "%STATIC_CP%" mkdir "%STATIC_CP%"
if exist "%STATIC_CP%\assets" rmdir /s /q "%STATIC_CP%\assets"
for %%F in ("%STATIC_CP%\index.html" "%STATIC_CP%\favicon.svg" "%STATIC_CP%\manifest.webmanifest") do if exist "%%~F" del /q "%%~F"
xcopy "%WEB_DIST%\*" "%STATIC_CP%\" /E /Y /I /Q >nul
if errorlevel 1 ( echo [build-frontend] deploy to classpath static failed & exit /b 1 )

echo [build-frontend] bake boot (inject __DSH_BOOT__ + __ModuleLoader__ facade + deploy plugin bundles)...
call node "%BAKE%" "%STATIC_RES%"
if errorlevel 1 ( echo [build-frontend] bake-boot source static failed & exit /b 1 )
call node "%BAKE%" "%STATIC_CP%"
if errorlevel 1 ( echo [build-frontend] bake-boot classpath static failed & exit /b 1 )

echo [build-frontend] done: self-contained static ready (dsh-app same-origin hosting, no dsh web injection needed)
exit /b 0
