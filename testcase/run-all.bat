@echo off
chcp 65001 >nul
setlocal
rem 一键端到端验证 —— 按开发模式分组覆盖全部场景：
rem   基础对话 / 会话与记忆 / 技能与编排（RPC） ; SSE / WebSocket / 前端交互（Web）
rem
rem 用法： testcase\run-all.bat
rem 依赖：mvn、curl、python（Windows 用 python，非 python3）、PowerShell；
rem       前端交互 E2E 另需 playwright + chromium。
rem 环境变量从仓库根 .env 自动加载（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

if exist "%ROOT%\.env" (
  for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /v /c:"#" "%ROOT%\.env"`) do set "%%a=%%b"
)

if not defined DEEPSEEK_API_KEY (
  echo [run-all] 未配置 DEEPSEEK_API_KEY：复制 .env.example 为 .env 填入 key 后再运行。
  exit /b 1
)
if not defined DSH_MODEL set "DSH_MODEL=deepseek-chat"
if not defined DSH_BASE_URL set "DSH_BASE_URL=https://api.deepseek.com"

rem 临时数据目录（hermetic：会话/技能隔离，不污染 %USERPROFILE%\.dsh）
set "DSH_DATA_DIR=%TEMP%\dsh-e2e-%RANDOM%-%RANDOM%"
mkdir "%DSH_DATA_DIR%" 2>nul

echo ============================================================
echo [run-all] 端到端验证  model=%DSH_MODEL%  baseUrl=%DSH_BASE_URL%
echo           临时数据目录: %DSH_DATA_DIR%
echo ============================================================

rem 1) 构建（install 刷新本地仓库 jar，确保 exec:java 与服务端 cp.txt 用到最新 dsh-sdk/dsh-web）
echo.
echo [run-all] 1/4 构建后端 + testcase 驱动（install）...
call mvn -q install -DskipTests -Dmaven.test.skip=true
if errorlevel 1 ( echo [run-all] 构建失败 1>&2 & exit /b 1 )

rem RpcE2e 以子进程启动 RPC 服务端；Windows 上 Java ProcessBuilder 不能直接 exec .bat，
rem 故用 "cmd /c start-rpc.bat" 作为 DSH_RPC_CMD（HarnessClient 按空白拆分命令行）。
set "DSH_RPC_CMD=cmd /c %ROOT%\scripts\start-rpc.bat"

rem 2) RPC E2E（基础对话 / 会话与记忆 / 技能与编排）
echo.
echo [run-all] 2/4 RPC E2E —— 基础对话 + 会话与记忆 + 技能与编排（stdio JSON-RPC，基于 dsh SDK 客户端）...
set rpc_ok=0
call mvn -q -pl testcase exec:java -Dexec.mainClass=com.deepseek.dsh.testcase.RpcE2e
if errorlevel 1 set rpc_ok=1

rem 3) Web 服务端（一个实例供 SSE + WebSocket 共用）
echo.
echo [run-all] 3/4 启动 Web 服务端（REST + SSE + WebSocket）...
start "" /B "%ROOT%\scripts\start-web.bat" 8765
set /a tries=0
:webwait
curl -sf http://localhost:8765/api/agent/health >nul 2>nul
if not errorlevel 1 goto webup
set /a tries+=1
if %tries% geq 60 goto webup
ping -n 2 127.0.0.1 >nul
goto webwait
:webup

rem 4a) Web SSE/HTTP E2E（流响应 / 完整返回）
echo.
echo [run-all] 4a/4 Web SSE/HTTP E2E（流响应 + 完整返回，模拟前端）...
set web_ok=0
call "%ROOT%\testcase\web-e2e.bat" 8765
if errorlevel 1 set web_ok=1

rem 4b) WebSocket E2E（并发多 session + 流式 + 取消）
echo.
echo [run-all] 4b/4 WebSocket E2E（并发多 session + 流式 + 取消）...
set ws_ok=0
python "%ROOT%\testcase\ws-e2e.py"
if errorlevel 1 set ws_ok=1

rem 4c) 前端真实交互 E2E（SPA 渲染->输入->发送->回复渲染，需 playwright + chromium）
echo.
echo [run-all] 4c/4 前端交互 E2E（chromium 驱动 SPA）...
set fe_ok=0
python -c "import playwright" >nul 2>nul
if errorlevel 1 (
  echo   [SKIP] 未安装 playwright（前端交互 E2E 跳过）
) else (
  python "%ROOT%\testcase\frontend-e2e.py"
  if errorlevel 1 set fe_ok=1
)

rem 清理：杀死监听 8765 的 java 进程 + 删除临时数据目录
for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr ":8765 " ^| findstr "LISTENING"') do taskkill /pid %%P /f >nul 2>nul
if exist "%DSH_DATA_DIR%" rd /s /q "%DSH_DATA_DIR%" >nul 2>nul

echo.
echo ============================================================
set "rpc_s=PASS" & if not "%rpc_ok%"=="0" set "rpc_s=FAIL"
set "web_s=PASS" & if not "%web_ok%"=="0" set "web_s=FAIL"
set "ws_s=PASS" & if not "%ws_ok%"=="0" set "ws_s=FAIL"
set "fe_s=PASS" & if not "%fe_ok%"=="0" set "fe_s=FAIL"
echo [run-all] 总结: RPC=%rpc_s%  Web=%web_s%  WebSocket=%ws_s%  Frontend=%fe_s%
echo ============================================================
if "%rpc_ok%%web_ok%%ws_ok%%fe_ok%"=="0000" ( exit /b 0 ) else ( exit /b 1 )
