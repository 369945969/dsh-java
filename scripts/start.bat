@echo off
chcp 65001 >nul
setlocal
rem 一键启动：编译后端 -> 启动 Web 服务（托管原版 Cordis 前端 shell + apiproxy 网关）。
rem 前端静态资源（原版 shell + __DSH_BOOT__ 启动快照 + 插件包）已构建并提交于
rem dsh-app/src/main/resources/static，由后端同源托管，无需运行时重建。
rem 打开 http://localhost:8765 即可用原版前端对话后端 agent。
rem
rem 用法： scripts\start.bat [port]
rem 环境变量从仓库根 .env 自动加载（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"
set "CP_FILE=%ROOT%\dsh-app\target\rpc-cp.txt"

rem 加载 .env（若存在；不提交密钥）
if exist "%ROOT%\.env" (
  for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /v /c:"#" "%ROOT%\.env"`) do set "%%a=%%b"
)
if not defined DSH_MODEL set "DSH_MODEL=deepseek-chat"
if not defined DSH_BASE_URL set "DSH_BASE_URL=https://api.deepseek.com"

rem 首次构建 classpath（任意模块 pom 变更即重建）
set "NEED_BUILD=0"
if not exist "%CP_FILE%" set "NEED_BUILD=1"
if "%NEED_BUILD%"=="0" (
  call :check_pom_newer "%ROOT%\pom.xml"
  call :check_pom_newer "%ROOT%\testcase\pom.xml"
  for /d %%D in (%ROOT%\dsh-*) do if exist "%%D\pom.xml" call :check_pom_newer "%%D\pom.xml"
)
if "%NEED_BUILD%"=="1" (
  echo [start] 首次构建 classpath... 1>&2
  call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app -am install -DskipTests -Dmaven.test.skip=true
  if errorlevel 1 ( echo [start] mvn install 失败 1>&2 & exit /b 1 )
  call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%CP_FILE%"
  if errorlevel 1 ( echo [start] mvn build-classpath 失败 1>&2 & exit /b 1 )
)

echo [start] 启动 Web 服务端: port=%PORT% model=%DSH_MODEL% 1>&2

rem classpath 超 8KB（cmd 行/env 上限 8191），用 java @argfile 规避截断
set "ARGF=%ROOT%\dsh-app\target\dsh-start-%RANDOM%.arg"
> "%ARGF%" echo -Dserver.port=%PORT%
>>"%ARGF%" echo -cp
<nul >>"%ARGF%" set /p "=%ROOT%\dsh-app\target\classes;"
>>"%ARGF%" type "%CP_FILE%"
>>"%ARGF%" echo.
>>"%ARGF%" echo com.deepseek.dsh.app.boot.DshApplication
java @%ARGF%
set "EXITCODE=%ERRORLEVEL%"
if exist "%ARGF%" del "%ARGF%"
exit /b %EXITCODE%

:check_pom_newer
rem %1 = pom 路径；若比 %CP_FILE% 新（或 cp 缺失）则置 NEED_BUILD=1
echo F| xcopy /D /L /Y "%~1" "%CP_FILE%" 2>nul | findstr /c:".xml" >nul && set "NEED_BUILD=1"
goto :eof
