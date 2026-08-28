@echo off
chcp 65001 >nul
setlocal
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
rem 环境变量同 start-rpc.bat（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL）。

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"
set "BASE=http://localhost:%PORT%"
set /a PASS=0
set /a FAIL=0

if exist "%ROOT%\.env" (
  for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /v /c:"#" "%ROOT%\.env"`) do set "%%a=%%b"
)

set "STARTED=0"
curl -sf "%BASE%/api/agent/health" >nul 2>nul
if errorlevel 1 (
  echo [web-e2e] 启动 Web 服务端 (port=%PORT%)... 1>&2
  start "" /B "%ROOT%\scripts\start-web.bat" %PORT%
  set "STARTED=1"
)

set /a tries=0
:wait
if "%STARTED%"=="0" goto health_up
curl -sf "%BASE%/api/agent/health" >nul 2>nul
if not errorlevel 1 goto health_up
set /a tries+=1
if %tries% geq 60 goto health_up
ping -n 2 127.0.0.1 >nul
goto wait
:health_up

rem 1) 健康检查
powershell -NoProfile -Command "if ((Invoke-RestMethod -Uri '%BASE%/api/agent/health' -UseBasicParsing -ErrorAction SilentlyContinue).status -eq 'ok') { exit 0 } else { exit 1 }"
if not errorlevel 1 ( call :pass "GET /api/agent/health" ) else ( call :fail "GET /api/agent/health" "未返回 ok" )

rem 2) 一次性对话（对瞬时模型失败重试 2 次）
set "TMPSEND=%TEMP%\dsh-send-%RANDOM%.json"
set "TMPSENDOUT=%TMPSEND%.out"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TMPSEND%', '{""message"":""你好，请用一句话介绍你自己。""}', [Text.Encoding]::UTF8)" 2>nul
set /a sendtry=1
:sendloop
curl -s -X POST "%BASE%/api/agent/send" -H "Content-Type: application/json" --data-binary "@%TMPSEND%" -o "%TMPSENDOUT%" 2>nul
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
curl -sN -X POST "%BASE%/api/agent/stream" -H "Content-Type: application/json" --data-binary "@%TMPSTREAMREQ%" -o "%TMPSTREAM%" 2>nul
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
