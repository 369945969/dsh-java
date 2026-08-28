@echo off
chcp 65001 >nul
setlocal
rem 启动 RPC 服务端（stdio newline-delimited JSON-RPC 2.0）—— 对应原 Harness 的
rem dsh-jsonrpc-agent 运行时。stdout 仅承载 JSON-RPC 帧，所有日志走 stderr
rem （logback-rpc.xml），保证 SDK 客户端可干净读取。
rem
rem 用法： scripts\start-rpc.bat
rem 环境变量（从仓库根 .env 自动加载，亦可手动 set）：
rem   DEEPSEEK_API_KEY  模型 API Key（必填）
rem   DSH_BASE_URL      OpenAI 兼容端点
rem   DSH_MODEL         模型名

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "CP_FILE=%ROOT%\dsh-app\target\rpc-cp.txt"

rem 加载 .env（若存在；不提交密钥）
if exist "%ROOT%\.env" (
  for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /v /c:"#" "%ROOT%\.env"`) do set "%%a=%%b"
)
if not defined DSH_MODEL set "DSH_MODEL=deepseek-chat"
if not defined DSH_BASE_URL set "DSH_BASE_URL=https://api.deepseek.com"

rem 首次或任意模块 pom 变更时重建 classpath
set "NEED_BUILD=0"
if not exist "%CP_FILE%" set "NEED_BUILD=1"
if "%NEED_BUILD%"=="0" (
  call :check_pom_newer "%ROOT%\pom.xml"
  call :check_pom_newer "%ROOT%\testcase\pom.xml"
  for /d %%D in (%ROOT%\dsh-*) do if exist "%%D\pom.xml" call :check_pom_newer "%%D\pom.xml"
)
if "%NEED_BUILD%"=="1" (
  echo [start-rpc] 首次构建 classpath（install + build-classpath）... 1>&2
  call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app -am install -DskipTests -Dmaven.test.skip=true
  if errorlevel 1 ( echo [start-rpc] mvn install 失败 1>&2 & exit /b 1 )
  call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%CP_FILE%"
  if errorlevel 1 ( echo [start-rpc] mvn build-classpath 失败 1>&2 & exit /b 1 )
)

echo [start-rpc] 启动 RPC 服务端: model=%DSH_MODEL% baseUrl=%DSH_BASE_URL% 1>&2

rem classpath 超 8KB（cmd 行/env 上限 8191），用 java @argfile 规避截断
set "ARGF=%ROOT%\dsh-app\target\dsh-rpc-%RANDOM%.arg"
> "%ARGF%" echo -Dlogback.configurationFile=logback-rpc.xml
>>"%ARGF%" echo -cp
<nul >>"%ARGF%" set /p "=%ROOT%\dsh-app\target\classes;"
>>"%ARGF%" type "%CP_FILE%"
>>"%ARGF%" echo.
>>"%ARGF%" echo com.deepseek.dsh.app.rpc.DshRpcServer
java @%ARGF%
set "EXITCODE=%ERRORLEVEL%"
if exist "%ARGF%" del "%ARGF%"
exit /b %EXITCODE%

:check_pom_newer
rem %1 = pom 路径；若比 %CP_FILE% 新（或 cp 缺失）则置 NEED_BUILD=1
echo F| xcopy /D /L /Y "%~1" "%CP_FILE%" 2>nul | findstr /c:".xml" >nul && set "NEED_BUILD=1"
goto :eof
