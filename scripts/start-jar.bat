@echo off
chcp 65001 >nul
setlocal
rem Launch the self-contained fat jar built by scripts\build-jar.bat -- equivalent of
rem start.bat but runs java -jar instead of -cp, so all deps + static frontend + logback
rem + application.yml live inside the jar (no external classpath/files). Open
rem http://localhost:8765 (or %PORT%) in a browser to use the frontend.
rem
rem On first launch the backend prints an authentication URL (token) to the console;
rem open http://localhost:<port>/?token=... once to complete the session handshake.
rem
rem Usage: scripts\start-jar.bat [port]
rem Model/key/endpoint come from dataDir\model-config.json (~/.dsh by default) -- this is
rem runtime config, not a code dependency; create it via the web "Add custom model" page.

set "SELF=start-jar"

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "PORT=%~1"
if not defined PORT set "PORT=8765"

rem --- Java version check: project requires Java 21 (jakarta + Spring Boot 3). ---
set "JAVABIN=java"
if defined JAVA_HOME set "JAVABIN=%JAVA_HOME%\bin\java.exe"
set "JV_TMP=%ROOT%\dsh_jv.txt"
"%JAVABIN%" -version >nul 2>"%JV_TMP%"
if errorlevel 1 ( echo [%SELF%] error: cannot run java, path=%JAVABIN% 1>&2 & del "%JV_TMP%" 2>nul & exit /b 1 )
set "JV_VER="
for /f "tokens=3" %%v in ('type "%JV_TMP%"') do if not defined JV_VER set "JV_VER=%%v"
del "%JV_TMP%" 2>nul
set "JV_VER=%JV_VER:"=%"
for /f "delims=." %%m in ("%JV_VER%") do set "JV_MAJOR=%%m"
if not "%JV_MAJOR%"=="21" (
  echo [%SELF%] error: Java 21 required, found %JV_VER% 1>&2
  echo [%SELF%]        JAVA_HOME=%JAVA_HOME% 1>&2
  echo [%SELF%]        set JAVA_HOME to a JDK 21 and retry, e.g. D:\Program Files\Java\jdk-21 1>&2
  exit /b 1
)
echo [%SELF%] java %JV_VER% ok 1>&2

rem --- Locate fat jar (built by build-jar.bat). dsh-app-*.jar matches the fat jar; the
rem     thin backup dsh-app-*.jar.original does not match *.jar. Require .original to
rem     exist as proof it is a fat jar (otherwise java -jar would fail on a thin jar). ---
set "JAR="
for %%F in ("%ROOT%\dsh-app\target\dsh-app-*.jar") do set "JAR=%%~fF"
if not defined JAR (
  echo [%SELF%] fat jar not found: run scripts\build-jar.bat first. 1>&2
  exit /b 1
)
if not exist "%JAR%.original" (
  echo [%SELF%] %JAR% is not a fat jar, .original backup missing; run scripts\build-jar.bat first. 1>&2
  exit /b 1
)

rem free port %PORT%: a running instance holds the port and locks the jar.
call :kill_port

rem Set a fixed launch token so it stays the same across restarts.
if not defined DSH_TOKEN set "DSH_TOKEN=ECkvAL8rG-BYj_ex_B8hleaq8mk88ncheFEor1SoDkg"
echo [%SELF%] launch token: %DSH_TOKEN% 1>&2

set "SRVLOG=%ROOT%\testcase\.auth\server.log"
if not exist "%ROOT%\testcase\.auth" mkdir "%ROOT%\testcase\.auth" >nul
break > "%SRVLOG%"

echo [%SELF%] launching fat jar: port=%PORT% 1>&2

rem java -jar uses the fat jar's own launcher (Spring Boot JarLauncher); -cp is ignored.
rem -Dserver.port overrides application.yml (default 8765). logback-spring.xml inside the
rem jar is auto-loaded; static frontend under classpath:/static is served same-origin.
rem Launch in background (non-blocking): java output goes to server.log
start "" /B cmd /c ""%JAVABIN%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dserver.port=%PORT% -jar "%JAR%" >> "%SRVLOG%" 2>&1"

echo [%SELF%] web server started in background (port=%PORT%)
echo [%SELF%] URL: http://localhost:%PORT%/?token=%DSH_TOKEN%
exit /b 0

:kill_port
rem free port %PORT%: netstat for LISTENING PID, taskkill /F /T the tree, retry until free
set /a KP_RETRY=0
:kp_loop
set "KP_PID="
for /f "tokens=5" %%a in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr ":%PORT%"') do if not defined KP_PID set "KP_PID=%%a"
if not defined KP_PID goto :kp_done
if %KP_RETRY%==0 echo [%SELF%] port %PORT% in use, killing old process PID=%KP_PID% 1>&2
taskkill /F /T /PID %KP_PID% >nul 2>&1
set /a KP_RETRY+=1
if %KP_RETRY% LSS 8 (
  ping -n 2 127.0.0.1 >nul
  goto :kp_loop
)
:kp_done
goto :eof
