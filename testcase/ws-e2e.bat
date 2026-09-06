@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem WebSocket E2E launcher: start server (if needed) + pass token to ws-e2e.ts
rem
rem Usage: testcase\ws-e2e.bat [web_port] [token]
rem   token: optional launch token. If omitted, parsed from server log.
rem Deps: Node 24+ (built-in WebSocket + TS strip-types), curl (Win10 1803+).

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"
set "BASE=http://localhost:%PORT%"

set "AUTH=%ROOT%\testcase\.auth"
set "SRVLOG=%AUTH%\server.log"
set "TOKEN_FILE=%AUTH%\token.txt"
if not exist "%AUTH%" mkdir "%AUTH%" >nul

rem ---- token from arg or env ----
set "TOKEN=%~2"
if not defined TOKEN set "TOKEN=%DSH_TOKEN%"

rem ---- start server only when port is free ----
set "STARTED=0"
set "CODE=000"
curl -s -o nul -m 2 -w "%%{http_code}" "%BASE%/" >"%AUTH%\portchk.txt" 2>nul
set /p CODE=<"%AUTH%\portchk.txt"
del "%AUTH%\portchk.txt" >nul 2>nul
if not "%CODE%"=="000" goto :server_ready
  echo [ws-e2e] starting web server (port=%PORT%)... 1>&2
  break > "%SRVLOG%"
  start "" /B cmd /c ""%ROOT%\scripts\start.bat" %PORT% >> "%SRVLOG%" 2>&1"
  set "STARTED=1"
:server_ready

rem ---- obtain token (from arg, or parsed from server log) ----
if not "!TOKEN!"=="" goto :got_token
if exist "%TOKEN_FILE%" set /p TOKEN=<"%TOKEN_FILE%"
if "!TOKEN!"=="" (
  set /a tktry=0
  :tkwait
  if exist "%SRVLOG%" for /f "usebackq tokens=2 delims==" %%T in (`findstr /C:"token=" "%SRVLOG%" 2^>nul`) do set "TOKEN=%%T"
  if not "!TOKEN!"=="" goto :got_token
  set /a tktry+=1
  if !tktry! geq 60 goto :no_token
  ping -n 2 127.0.0.1 >nul
  goto :tkwait
)
:got_token
echo !TOKEN!> "%TOKEN_FILE%"

rem ---- wait for health check ----
set /a hwtry=0
:health_wait
curl -s "%BASE%/api/agent/health" -m 2 | findstr /C:"\"status\":\"ok\"" >nul 2>nul
if not errorlevel 1 goto :health_up
set /a hwtry+=1
if %hwtry% geq 30 goto :health_up
ping -n 2 127.0.0.1 >nul
goto :health_wait
:health_up

rem ---- run TypeScript test (token->cookie exchange happens inside) ----
echo [ws-e2e] running ws-e2e.ts ...
set "DSH_PORT=%PORT%"
set "DSH_TOKEN=!TOKEN!"
node "%ROOT%\testcase\ws-e2e.ts"
set "WS_EXIT=%errorlevel%"

if "%STARTED%"=="1" (
  for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr ":%PORT% " ^| findstr "LISTENING"') do taskkill /pid %%P /f >nul 2>nul
)

exit /b %WS_EXIT%

:no_token
echo [ws-e2e] [FAIL] could not obtain launch token 1>&2
exit /b 1
