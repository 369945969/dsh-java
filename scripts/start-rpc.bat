@echo off
chcp 65001 >nul
setlocal
rem Launch the RPC server (stdio newline-delimited JSON-RPC 2.0) - the original Harness
rem dsh-jsonrpc-agent runtime. stdout carries only JSON-RPC frames; all logs go to stderr
rem (logback-rpc.xml) so SDK clients can read cleanly.
rem
rem Usage: scripts\start-rpc.bat
rem Env vars (auto-loaded from repo root .env, or set manually):
rem   DEEPSEEK_API_KEY  model API key (required)
rem   DSH_BASE_URL      OpenAI-compatible endpoint
rem   DSH_MODEL         model name

set "SELF=start-rpc"

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

set "CP_FILE=%ROOT%\dsh-app\target\rpc-cp.txt"

rem --- Java version check: project requires Java 21 (jakarta + Spring Boot 3). mvn uses JAVA_HOME,
rem     runtime uses the same java. If not 21, error out early. ---
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

rem load .env if present (do not commit secrets)
if not exist "%ROOT%\.env" goto :skip_env
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /v /c:"#" "%ROOT%\.env"`) do set "%%a=%%b"
:skip_env
if not defined DSH_MODEL set "DSH_MODEL=deepseek-chat"
if not defined DSH_BASE_URL set "DSH_BASE_URL=https://api.deepseek.com"

rem recompile backend before launch (clean install picks up any .java/pom change), then refresh classpath
echo [%SELF%] recompiling backend (mvn clean install)... 1>&2
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true || ( echo [%SELF%] mvn clean install failed 1>&2 & exit /b 1 )
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%CP_FILE%" || ( echo [%SELF%] mvn build-classpath failed 1>&2 & exit /b 1 )

echo [%SELF%] launching RPC server: model=%DSH_MODEL% baseUrl=%DSH_BASE_URL% 1>&2

rem Classpath may exceed 8KB and the .m2 path contains spaces. java @argfile cannot quote-group
rem backslashed Windows paths, so launch via PowerShell + ProcessStartInfo: CreateProcess allows
rem ~32KB command line and the CRT parses -cp "..." correctly.
powershell -NoProfile -Command "$cp=[IO.File]::ReadAllText('%CP_FILE%').TrimEnd(); $cp='%ROOT%\dsh-app\target\classes;'+$cp; $q=[string][char]34; $psi=New-Object Diagnostics.ProcessStartInfo; $psi.FileName='%JAVABIN%'; $psi.Arguments='-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dlogback.configurationFile=logback-rpc.xml -cp '+$q+$cp+$q+' com.deepseek.dsh.app.rpc.DshRpcServer'; $psi.UseShellExecute=$false; $p=[Diagnostics.Process]::Start($psi); $p.WaitForExit(); exit $p.ExitCode"
exit /b %ERRORLEVEL%
