@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem Web end-to-end verification: simulate the frontend HTTP/SSE flow against the backend Web face (REST + SSE).
rem Helps users wire their own frontend: this script covers every HTTP contract the frontend calls.
rem
rem Covers:
rem   GET  /api/agent/health      health check
rem   POST /api/agent/send        one-shot chat (returns full reply + history)
rem   POST /api/agent/stream     SSE streaming chat (session->delta*->done)
rem
rem Usage: testcase\web-e2e.bat [web_port]
rem Deps: curl (built in on Win10 1803+), findstr (built in); JSON validation via PowerShell.
rem Model comes from dataDir\model-config.json (web-saved active profile); no env vars.
rem
rem Auth: backend /api requires a browser session cookie. After starting (or reusing) the server,
rem parse the launch token from start.bat's stderr and exchange it via GET /?token=<token> for a
rem dsh-auth cookie (curl -c cookie.jar); all subsequent /api calls carry -b cookie.jar.

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"
set "BASE=http://localhost:%PORT%"
set /a PASS=0
set /a FAIL=0

rem shared auth state (run-all.bat and web-e2e.bat share the same path so a running server can be reused)
set "AUTH=%ROOT%\testcase\.auth"
set "SRVLOG=%AUTH%\server.log"
set "TOKEN_FILE=%AUTH%\token.txt"
set "COOKIE=%AUTH%\cookie.jar"
if not exist "%AUTH%" mkdir "%AUTH%" >nul

rem start the bundled server only when the port is free (probe the index page, not the /api auth state)
set "STARTED=0"
set "CODE=000"
curl -s -o nul -m 2 -w "%%{http_code}" "%BASE%/" >"%AUTH%\portchk.txt" 2>nul
set /p CODE=<"%AUTH%\portchk.txt"
del "%AUTH%\portchk.txt" >nul 2>nul
if not "%CODE%"=="000" goto :server_ready
  echo [web-e2e] starting web server (port=%PORT%)... 1>&2
  break > "%SRVLOG%"
  start "" /B cmd /c ""%ROOT%\scripts\start.bat" %PORT% >> "%SRVLOG%" 2>&1"
  set "STARTED=1"
:server_ready

rem parse the launch token and exchange it for a cookie (handshake): wait for the token to appear in the log.
rem When starting our own server, force a fresh handshake (do not reuse a stale cookie from a previous run);
rem when reusing a running server (e.g. started by run-all.bat), prefer the existing token/cookie, else parse the log.
set "TOKEN="
if "%STARTED%"=="1" goto :force_handshake
if exist "%TOKEN_FILE%" (
  set /p TOKEN=<"%TOKEN_FILE%"
  if "!TOKEN!"=="" goto :force_handshake
  if not exist "%COOKIE%" goto :force_handshake
  goto :handshake_done
)
:force_handshake
break > "%TOKEN_FILE%"
break > "%COOKIE%"
set /a tktry=0
:tkwait
set "TOKEN="
if exist "%SRVLOG%" for /f "usebackq tokens=2 delims==" %%T in (`findstr /C:"token=" "%SRVLOG%" 2^>nul`) do set "TOKEN=%%T"
if not "!TOKEN!"=="" goto :got_token
set /a tktry+=1
if %tktry% geq 60 goto :handshake_done
ping -n 2 127.0.0.1 >nul
goto :tkwait
:got_token
echo !TOKEN!> "%TOKEN_FILE%"
curl -s -o nul "%BASE%/?token=!TOKEN!" -c "%COOKIE%"
:handshake_done
if "!TOKEN!"=="" (
  echo [web-e2e] [FAIL] could not obtain auth token/cookie (port %PORT% may be held by an external service with no token log)
  exit /b 1
)
if not exist "%COOKIE%" (
  echo [web-e2e] [FAIL] could not obtain auth token/cookie (port %PORT% may be held by an external service with no token log)
  exit /b 1
)

rem wait for the health check (with cookie) to return ok
set /a hwtry=0
:health_wait
curl -s -b "%COOKIE%" "%BASE%/api/agent/health" | findstr /C:"\"status\":\"ok\"" >nul 2>nul
if not errorlevel 1 goto :health_up
set /a hwtry+=1
if %hwtry% geq 30 goto :health_up
ping -n 2 127.0.0.1 >nul
goto :health_wait
:health_up

rem 1) health check
curl -s -b "%COOKIE%" "%BASE%/api/agent/health" | findstr /C:"\"status\":\"ok\"" >nul 2>nul
if not errorlevel 1 ( call :pass "GET /api/agent/health" ) else ( call :fail "GET /api/agent/health" "did not return ok" )

rem 2) one-shot chat (retry twice on transient model failure)
set "TMPSEND=%TEMP%\dsh-send-%RANDOM%.json"
set "TMPSENDOUT=%TMPSEND%.out"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TMPSEND%', '{""message"":""Hello, introduce yourself in one sentence.""}', [Text.Encoding]::UTF8)" 2>nul
set /a sendtry=1
:sendloop
curl -s -b "%COOKIE%" -X POST "%BASE%/api/agent/send" -H "Content-Type: application/json" --data-binary "@%TMPSEND%" -o "%TMPSENDOUT%" 2>nul
powershell -NoProfile -Command "$j=Get-Content -Raw '%TMPSENDOUT%' -ErrorAction SilentlyContinue | ConvertFrom-Json; if($j -and $j.reply -and $j.reply.Length -gt 0){exit 0}else{exit 1}" 2>nul
if not errorlevel 1 goto send_pass
if %sendtry% geq 3 goto send_fail
set /a sendtry+=1
ping -n 3 127.0.0.1 >nul
goto sendloop
:send_pass
call :pass "POST /api/agent/send"
powershell -NoProfile -Command "$j=Get-Content -Raw '%TMPSENDOUT%' | ConvertFrom-Json; Write-Host ('    reply: ' + $j.reply.Substring(0,[Math]::Min(120,$j.reply.Length)))"
goto after_send
:send_fail
call :fail "POST /api/agent/send" "reply empty or abnormal"
:after_send

rem 3) SSE streaming chat
set "TMPSTREAMREQ=%TEMP%\dsh-stream-req-%RANDOM%.json"
set "TMPSTREAM=%TEMP%\dsh-stream-%RANDOM%.txt"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TMPSTREAMREQ%', '{""message"":""Say one more sentence.""}', [Text.Encoding]::UTF8)" 2>nul
curl -sN -b "%COOKIE%" -X POST "%BASE%/api/agent/stream" -H "Content-Type: application/json" --data-binary "@%TMPSTREAMREQ%" -o "%TMPSTREAM%" 2>nul
findstr /c:"event:session" "%TMPSTREAM%" >nul
if errorlevel 1 goto stream_fail
findstr /c:"event:delta" "%TMPSTREAM%" >nul
if errorlevel 1 goto stream_fail
findstr /c:"event:done" "%TMPSTREAM%" >nul
if errorlevel 1 goto stream_fail
findstr /c:"[DONE]" "%TMPSTREAM%" >nul
if errorlevel 1 goto stream_fail
call :pass "POST /api/agent/stream (SSE: session->delta*->done)"
set /a deltacount=0
for /f %%n in ('findstr /c:"event:delta" "%TMPSTREAM%" 2^>nul') do set /a deltacount+=1
echo    received %deltacount% delta frames
goto after_stream
:stream_fail
call :fail "POST /api/agent/stream" "SSE frames incomplete"
:after_stream

echo.
echo [web-e2e] result: %PASS% passed, %FAIL% failed

if "%STARTED%"=="1" (
  rem cleanup: kill the process listening on %PORT% (note: kills any listener on that port)
  for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr ":%PORT% " ^| findstr "LISTENING"') do taskkill /pid %%P /f >nul 2>nul
)
if exist "%TMPSEND%" del "%TMPSEND%" >nul 2>nul
if exist "%TMPSENDOUT%" del "%TMPSENDOUT%" >nul 2>nul
if exist "%TMPSTREAM%" del "%TMPSTREAM%" >nul 2>nul
if exist "%TMPSTREAMREQ%" del "%TMPSTREAMREQ%" >nul 2>nul
if %FAIL%==0 exit /b 0
exit /b 1

:pass
echo   [PASS] %~1
set /a PASS+=1
goto :eof

:fail
echo   [FAIL] %~1 - %~2
set /a FAIL+=1
goto :eof
