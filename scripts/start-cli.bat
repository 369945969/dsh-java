@echo off
chcp 65001 >nul
setlocal
rem 启动 CLI 交互终端（REPL）—— 对应原 Harness 的 dsh 默认交互模式。
rem 从 stdin 逐行读取用户输入，驱动 agent 对话，回复打印到 stdout。
rem 支持 /exit 退出、/new 新会话、/tokens 查看累计用量；会话跨多轮保持记忆。
rem
rem 用法： scripts\start-cli.bat
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

rem 首次或任意模块 pom 变更时重建 classpath（与其它启动脚本共用缓存）
set "NEED_BUILD=0"
if not exist "%CP_FILE%" set "NEED_BUILD=1"
if "%NEED_BUILD%"=="0" (
  call :check_pom_newer "%ROOT%\pom.xml"
  call :check_pom_newer "%ROOT%\testcase\pom.xml"
  for /d %%D in (%ROOT%\dsh-*) do if exist "%%D\pom.xml" call :check_pom_newer "%%D\pom.xml"
)
if "%NEED_BUILD%"=="1" (
  echo [start-cli] 首次构建 classpath（install + build-classpath）... 1>&2
  call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app -am install -DskipTests -Dmaven.test.skip=true
  if errorlevel 1 ( echo [start-cli] mvn install 失败 1>&2 & exit /b 1 )
  call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%CP_FILE%"
  if errorlevel 1 ( echo [start-cli] mvn build-classpath 失败 1>&2 & exit /b 1 )
)

echo [start-cli] 启动 CLI 交互终端: model=%DSH_MODEL% baseUrl=%DSH_BASE_URL% 1>&2

rem classpath 超 8KB（cmd 行/env 上限 8191），用 java @argfile 规避截断
set "ARGF=%ROOT%\dsh-app\target\dsh-cli-%RANDOM%.arg"
> "%ARGF%" echo -Dlogback.configurationFile=logback-cli.xml
>>"%ARGF%" echo -cp
<nul >>"%ARGF%" set /p "=%ROOT%\dsh-app\target\classes;"
>>"%ARGF%" type "%CP_FILE%"
>>"%ARGF%" echo.
>>"%ARGF%" echo com.deepseek.dsh.app.cli.DshRepl
java @%ARGF%
set "EXITCODE=%ERRORLEVEL%"
if exist "%ARGF%" del "%ARGF%"
exit /b %EXITCODE%

:check_pom_newer
rem %1 = pom 路径；若比 %CP_FILE% 新（或 cp 缺失）则置 NEED_BUILD=1
echo F| xcopy /D /L /Y "%~1" "%CP_FILE%" 2>nul | findstr /c:".xml" >nul && set "NEED_BUILD=1"
goto :eof
