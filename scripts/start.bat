@echo off
chcp 65001 >nul
setlocal
rem One-click start: build backend -> launch Web server (hosts the vendored Cordis frontend shell + apiproxy gateway).
rem Frontend static assets (shell + __DSH_BOOT__ snapshot + plugin bundles) are built and committed under
rem dsh-app/src/main/resources/static, served same-origin by the backend; no runtime rebuild needed.
rem Open http://localhost:8765 to use the frontend against the backend agent.
rem
rem Usage: scripts\start.bat [port]
rem Model/key/endpoint come from dataDir\model-config.json (the active profile
rem saved via the web "Add custom model" page); no env vars needed.

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"
set "CP_FILE=%ROOT%\dsh-app\target\rpc-cp.txt"

rem --- Java version check: project requires Java 21 (jakarta + Spring Boot 3). mvn uses JAVA_HOME,
rem     runtime uses the same java. If not 21, error out early instead of confusing build/launch failures. ---
set "JAVABIN=java"
if defined JAVA_HOME set "JAVABIN=%JAVA_HOME%\bin\java.exe"
set "JV_TMP=%ROOT%\dsh_jv.txt"
"%JAVABIN%" -version >nul 2>"%JV_TMP%"
if errorlevel 1 ( echo [start] error: cannot run java, path=%JAVABIN% 1>&2 & del "%JV_TMP%" 2>nul & exit /b 1 )
set "JV_VER="
for /f "tokens=3" %%v in ('type "%JV_TMP%"') do if not defined JV_VER set "JV_VER=%%v"
del "%JV_TMP%" 2>nul
set "JV_VER=%JV_VER:"=%"
for /f "delims=." %%m in ("%JV_VER%") do set "JV_MAJOR=%%m"
if not "%JV_MAJOR%"=="21" (
  echo [start] error: Java 21 required, found %JV_VER% 1>&2
  echo [start]        JAVA_HOME=%JAVA_HOME% 1>&2
  echo [start]        set JAVA_HOME to a JDK 21 and retry, e.g. D:\Program Files\Java\jdk-21 1>&2
  exit /b 1
)
echo [start] java %JV_VER% ok 1>&2

rem free port %PORT%: a running instance holds the port and locks jars.
call :kill_port

rem No build here: run scripts\build-backend.bat first to produce target\classes + rpc-cp.txt, then launch.
if not exist "%CP_FILE%" (
  echo [start] %CP_FILE% not found: run scripts\build-backend.bat to build the backend first. 1>&2
  exit /b 1
)

rem Set a fixed launch token so it stays the same across restarts.
rem Override by setting DSH_TOKEN env var before running this script.
if not defined DSH_TOKEN set "DSH_TOKEN=ECkvAL8rG-BYj_ex_B8hleaq8mk88ncheFEor1SoDkg"
echo [start] launch token: %DSH_TOKEN% 1>&2

echo [start] launching web server: port=%PORT% (model from model-config.json) 1>&2

rem Write argfile (classpath may exceed 8KB; java @argfile handles this correctly)
set "ARGF=%ROOT%\dsh-app\target\dsh-web-%RANDOM%.arg"
set "SRVLOG=%ROOT%\testcase\.auth\server.log"
if not exist "%ROOT%\testcase\.auth" mkdir "%ROOT%\testcase\.auth" >nul
break > "%SRVLOG%"
powershell -NoProfile -Command "$cp='%ROOT%\dsh-app\target\classes;'+[IO.File]::ReadAllText('%CP_FILE%').TrimEnd(); $cp=$cp.Replace('\','\\'); $n=[char]10; $q=[char]34; [IO.File]::WriteAllText('%ARGF%', '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dserver.port=%PORT%'+$n+'-cp'+$n+$q+$cp+$q+$n+'com.deepseek.dsh.app.boot.DshApplication'+$n)"
if errorlevel 1 ( echo [start] failed to write argfile 1>&2 & exit /b 1 )

rem Launch in background (non-blocking): java output goes to server.log
start "" /B cmd /c ""%JAVABIN%" @%ARGF% >> "%SRVLOG%" 2>&1"

echo [start] web server started in background (port=%PORT%)
echo [start] URL: http://localhost:%PORT%/?token=%DSH_TOKEN%
exit /b 0

:kill_port
rem free port %PORT%: netstat for LISTENING PID, taskkill /F /T the tree, retry until free
set /a KP_RETRY=0
:kp_loop
set "KP_PID="
for /f "tokens=5" %%a in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr ":%PORT%"') do if not defined KP_PID set "KP_PID=%%a"
if not defined KP_PID goto :kp_done
if %KP_RETRY%==0 echo [start] port %PORT% in use, killing old process PID=%KP_PID% 1>&2
taskkill /F /T /PID %KP_PID% >nul 2>&1
set /a KP_RETRY+=1
if %KP_RETRY% LSS 8 (
  ping -n 2 127.0.0.1 >nul
  goto :kp_loop
)
:kp_done
goto :eof
