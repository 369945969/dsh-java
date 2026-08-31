@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem Web 端端到端验证 —— 模拟前端 HTTP/SSE 交互，验证后端 Web 面（REST + SSE）。
rem 方便用户接自己的前端：本脚本覆盖前端会调用的全部 HTTP 契约。
rem
rem 覆盖：
rem   GET  /api/agent/health      健康检查
rem   POST /api/agent/send        一次性对话（返回完整回复+历史）
rem   POST /api/agent/stream     SSE 流式对话（session->delta*->done）
rem
rem 用法： testcase\web-e2e.bat [web_port]
rem 依赖：curl（Win10 1803+ 自带）、findstr（系统自带）；JSON 校验用 PowerShell。
rem 模型取自 dataDir\model-config.json（网页保存的活跃档案），无需环境变量。
rem
rem 认证：后端 /api 需要浏览器会话 cookie。本脚本启动（或复用）服务端后，从
rem start.bat 捕获的 stderr 解析启动令牌，用 GET /?token=<token> 换取
rem dsh-auth cookie（curl -c cookie.jar），后续所有 /api 调用带 -b cookie.jar。

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"
set "BASE=http://localhost:%PORT%"
set /a PASS=0
set /a FAIL=0

rem 认证共享状态（run-all.bat 与 web-e2e.bat 共用同一路径，便于复用已启动的服务端）
set "AUTH=%ROOT%\testcase\.auth"
set "SRVLOG=%AUTH%\server.log"
set "TOKEN_FILE=%AUTH%\token.txt"
set "COOKIE=%AUTH%\cookie.jar"
if not exist "%AUTH%" mkdir "%AUTH%" >nul

rem 仅当端口未占用时启动自带服务端（用索引页探测端口，不看 /api 认证状态）
set "STARTED=0"
set "CODE=000"
curl -s -o nul -m 2 -w "%%{http_code}" "%BASE%/" >"%AUTH%\portchk.txt" 2>nul
set /p CODE=<"%AUTH%\portchk.txt"
del "%AUTH%\portchk.txt" >nul 2>nul
if not "%CODE%"=="000" goto :server_ready
  echo [web-e2e] 启动 Web 服务端 (port=%PORT%)... 1>&2
  break > "%SRVLOG%"
  start "" /B cmd /c ""%ROOT%\scripts\start.bat" %PORT% >> "%SRVLOG%" 2>&1"
  set "STARTED=1"
:server_ready

rem 解析启动令牌并换取 cookie（握手）：等待 token 出现在日志里
rem 正在启动本服务端则强制重握手（避免沿用上一次运行的过期 cookie）；复用已运行
rem 服务端（如 run-all.bat 已启动）时，优先用现成 token/cookie，否则从日志解析。
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
  echo [web-e2e] [FAIL] 无法获取认证令牌/cookie（端口 %PORT% 可能被外部服务占用且无 token 日志）
  exit /b 1
)
if not exist "%COOKIE%" (
  echo [web-e2e] [FAIL] 无法获取认证令牌/cookie（端口 %PORT% 可能被外部服务占用且无 token 日志）
  exit /b 1
)

rem 等健康检查（带 cookie）返回 ok
set /a hwtry=0
:health_wait
curl -s -b "%COOKIE%" "%BASE%/api/agent/health" | findstr /C:"\"status\":\"ok\"" >nul 2>nul
if not errorlevel 1 goto :health_up
set /a hwtry+=1
if %hwtry% geq 30 goto :health_up
ping -n 2 127.0.0.1 >nul
goto :health_wait
:health_up

rem 1) 健康检查
curl -s -b "%COOKIE%" "%BASE%/api/agent/health" | findstr /C:"\"status\":\"ok\"" >nul 2>nul
if not errorlevel 1 ( call :pass "GET /api/agent/health" ) else ( call :fail "GET /api/agent/health" "未返回 ok" )

rem 2) 一次性对话（对瞬时模型失败重试 2 次）
set "TMPSEND=%TEMP%\dsh-send-%RANDOM%.json"
set "TMPSENDOUT=%TMPSEND%.out"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TMPSEND%', '{""message"":""你好，请用一句话介绍你自己。""}', [Text.Encoding]::UTF8)" 2>nul
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
powershell -NoProfile -Command "$j=Get-Content -Raw '%TMPSENDOUT%' | ConvertFrom-Json; Write-Host ('    回复: ' + $j.reply.Substring(0,[Math]::Min(120,$j.reply.Length)))"
goto after_send
:send_fail
call :fail "POST /api/agent/send" "回复为空或异常"
:after_send

rem 3) SSE 流式对话
set "TMPSTREAMREQ=%TEMP%\dsh-stream-req-%RANDOM%.json"
set "TMPSTREAM=%TEMP%\dsh-stream-%RANDOM%.txt"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TMPSTREAMREQ%', '{""message"":""再说一句话。""}', [Text.Encoding]::UTF8)" 2>nul
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
echo    收到 %deltacount% 个 delta 帧
goto after_stream
:stream_fail
call :fail "POST /api/agent/stream" "SSE 帧不完整"
:after_stream

echo.
echo [web-e2e] 结果: %PASS% 通过, %FAIL% 失败

if "%STARTED%"=="1" (
  rem 清理：杀死监听 %PORT% 的进程（注意：会杀掉该端口上的任意监听进程）
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
