@echo off
chcp 65001 >nul
setlocal
rem One-click start: build backend -> launch Web server (hosts the vendored Cordis frontend shell + apiproxy gateway).
rem Frontend static assets (shell + __DSH_BOOT__ snapshot + plugin bundles) are built and committed under
rem dsh-app/src/main/resources/static, served same-origin by the backend; no runtime rebuild needed.
rem Open http://localhost:8765 to use the frontend against the backend agent.
rem
rem Usage: scripts\start.bat [port]
rem Env vars loaded from repo root .env (DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL).

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

rem load .env if present (do not commit secrets)
if not exist "%ROOT%\.env" goto :skip_env
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /v /c:"#" "%ROOT%\.env"`) do set "%%a=%%b"
:skip_env
if not defined DSH_MODEL set "DSH_MODEL=deepseek-chat"
if not defined DSH_BASE_URL set "DSH_BASE_URL=https://api.deepseek.com"

rem recompile backend before launch (clean install picks up any .java/pom change), then refresh classpath
echo [start] recompiling backend (mvn clean install)... 1>&2
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true || ( echo [start] mvn clean install failed 1>&2 & exit /b 1 )
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%CP_FILE%" || ( echo [start] mvn build-classpath failed 1>&2 & exit /b 1 )

echo [start] launching web server: port=%PORT% model=%DSH_MODEL% 1>&2

call :kill_port

rem Launch via PowerShell + ProcessStartInfo, redirect output to capture token URL.
powershell -NoProfile -Command "$cp=[IO.File]::ReadAllText('%CP_FILE%').TrimEnd(); $cp='%ROOT%\dsh-app\target\classes;'+$cp; $q=[string][char]34; $psi=New-Object Diagnostics.ProcessStartInfo; $psi.FileName='%JAVABIN%'; $psi.Arguments='-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dserver.port=%PORT% -cp '+$q+$cp+$q+' com.deepseek.dsh.app.boot.DshApplication'; $psi.UseShellExecute=$false; $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true; $p=[Diagnostics.Process]::Start($psi); $p.ErrorData.Add_DataReceivedHandler({param($s,$e); if($e.Data){[Console]::Error.WriteLine($e.Data); if($e.Data -match 'authentication URL:'){$url=($e.Data -replace '.*authentication URL: ',''); [Console]::Error.WriteLine(''); [Console]::Error.WriteLine('================================================'); [Console]::Error.WriteLine($url); [Console]::Error.WriteLine('================================================'); [Console]::Error.WriteLine('')}}}); $p.OutputData.Add_DataReceivedHandler({param($s,$e); if($e.Data){[Console]::Error.WriteLine($e.Data)}}); $p.BeginErrorReadLine(); $p.BeginOutputReadLine(); $p.WaitForExit(); exit $p.ExitCode"
exit /b %ERRORLEVEL%

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
