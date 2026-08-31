@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem One-shot end-to-end verification, grouped by development mode:
rem   basic chat / session+memory / skill+orchestration (RPC) ; SSE / WebSocket / frontend interaction (Web)
rem
rem Usage: testcase\run-all.bat
rem Deps: mvn, curl, python (Windows uses python, not python3), PowerShell;
rem       frontend interaction E2E additionally needs playwright + chromium.
rem Model comes from dataDir\model-config.json (the active profile saved via the web UI); no env vars.

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

echo ============================================================
echo [run-all] end-to-end verification  model from %USERPROFILE%\.dsh\model-config.json (web-saved active profile)
echo ============================================================

rem 1) build (install refreshes the local repo jars so exec:java and the server cp.txt pick up the latest dsh-sdk/dsh-web)
echo.
echo [run-all] 1/4 build backend + testcase driver (install)...
call mvn -q install -DskipTests -Dmaven.test.skip=true
if errorlevel 1 ( echo [run-all] build failed 1>&2 & exit /b 1 )

rem RpcE2e spawns the RPC server as a subprocess; on Windows Java ProcessBuilder cannot exec .bat directly,
rem so use "cmd /c start-rpc.bat" as DSH_RPC_CMD (HarnessClient splits the command line by whitespace).
set "DSH_RPC_CMD=cmd /c %ROOT%\scripts\start-rpc.bat"

rem 2) RPC E2E (basic chat / session+memory / skill+orchestration)
echo.
echo [run-all] 2/4 RPC E2E -- basic chat + session+memory + skill+orchestration (stdio JSON-RPC, dsh SDK client)...
set rpc_ok=0
call mvn -q -pl testcase exec:java -Dexec.mainClass=com.deepseek.dsh.testcase.RpcE2e
if errorlevel 1 set rpc_ok=1

rem 3) web server (one instance shared by SSE + WebSocket)
set "AUTH=%ROOT%\testcase\.auth"
set "SRVLOG=%AUTH%\server.log"
set "TOKEN_FILE=%AUTH%\token.txt"
set "COOKIE=%AUTH%\cookie.jar"
if not exist "%AUTH%" mkdir "%AUTH%" >nul
echo.
echo [run-all] 3/4 start web server (REST + SSE + WebSocket)...
break > "%SRVLOG%"
start "" /B cmd /c ""%ROOT%\scripts\start.bat" 8765 >> "%SRVLOG%" 2>&1"
set "WEB_PID="

rem parse the launch token and exchange it for a cookie (same handshake as web-e2e.bat); web-e2e.bat reuses this instance
set "TOKEN="
set /a tktry=0
:tkwait
set "TOKEN="
if exist "%SRVLOG%" for /f "usebackq tokens=2 delims==" %%T in (`findstr /C:"token=" "%SRVLOG%" 2^>nul`) do set "TOKEN=%%T"
if not "!TOKEN!"=="" goto :got_token
set /a tktry+=1
if %tktry% geq 60 goto :tk_timeout
ping -n 2 127.0.0.1 >nul
goto :tkwait
:got_token
echo !TOKEN!> "%TOKEN_FILE%"
break > "%COOKIE%"
curl -s -o nul "http://localhost:8765/?token=!TOKEN!" -c "%COOKIE%"
set /a hwtry=0
:webwait
curl -s -b "%COOKIE%" http://localhost:8765/api/agent/health | findstr /C:"\"status\":\"ok\"" >nul 2>nul
if not errorlevel 1 goto :webup
set /a hwtry+=1
if %hwtry% geq 30 goto :webup
ping -n 2 127.0.0.1 >nul
goto :webwait
:webup
goto :skill_check
:tk_timeout
echo [run-all] [FAIL] could not obtain launch token (see %SRVLOG%) 1>&2
set "skill_ok=1"
set "web_ok=1"
goto :web_e2e
:skill_check

rem skill.list (apiproxy talks to the real SkillRegistry; should return a valid skill list)
echo.
echo [run-all] skill.list verification (apiproxy returns skill list)...
set skill_ok=0
curl -s -b "%COOKIE%" -X POST http://localhost:8765/api/skill.list -H "Content-Type: application/json" -d "{""rpcId"":""e2e"",""payload"":{}}" | findstr /C:"\"count\"" >nul || set skill_ok=1

:web_e2e
rem 4a) web SSE/HTTP E2E (streaming reply / full reply)
echo.
echo [run-all] 4a/4 Web SSE/HTTP E2E (streaming + full reply, simulating the frontend)...
if not defined web_ok set "web_ok=0"
call "%ROOT%\testcase\web-e2e.bat" 8765
if errorlevel 1 set web_ok=1

rem 4b) WebSocket E2E (concurrent multi-session + streaming + cancel)
echo.
echo [run-all] 4b/4 WebSocket E2E (concurrent multi-session + streaming + cancel)...
set ws_ok=0
python "%ROOT%\testcase\ws-e2e.py"
if errorlevel 1 set ws_ok=1

rem 4c) frontend real-interaction E2E (SPA render -> input -> send -> reply render; needs playwright + chromium)
echo.
echo [run-all] 4c/4 frontend interaction E2E (chromium-driven SPA)...
set fe_ok=0
python -c "import playwright" >nul 2>nul
if errorlevel 1 (
  echo   [SKIP] playwright not installed (frontend interaction E2E skipped)
) else (
  python "%ROOT%\testcase\frontend-e2e.py"
  if errorlevel 1 set fe_ok=1
)

rem cleanup: kill the java process listening on 8765
for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr ":8765 " ^| findstr "LISTENING"') do taskkill /pid %%P /f >nul 2>nul

echo.
echo ============================================================
set "rpc_s=PASS" & if not "%rpc_ok%"=="0" set "rpc_s=FAIL"
set "web_s=PASS" & if not "%web_ok%"=="0" set "web_s=FAIL"
set "ws_s=PASS" & if not "%ws_ok%"=="0" set "ws_s=FAIL"
set "fe_s=PASS" & if not "%fe_ok%"=="0" set "fe_s=FAIL"
set "skill_s=PASS" & if not "%skill_ok%"=="0" set "skill_s=FAIL"
echo [run-all] summary: RPC=%rpc_s%  Web=%web_s%  WebSocket=%ws_s%  Frontend=%fe_s%  Skill=%skill_s%
echo ============================================================
if "%rpc_ok%%web_ok%%ws_ok%%fe_ok%%skill_ok%"=="00000" ( exit /b 0 ) else ( exit /b 1 )
