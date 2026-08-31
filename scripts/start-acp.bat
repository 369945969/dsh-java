@echo off
chcp 65001 >nul
setlocal
rem Launch the ACP server (Automation-only Agent Client Protocol, stdio newline-delimited
rem JSON-RPC 2.0) - the original Harness dsh acp mode. stdout carries only JSON-RPC frames;
rem all logs go to stderr (logback-rpc.xml) so automation clients can read cleanly.
rem
rem Method surface (automation-only minimal set): session.create / session.run / session.list / shutdown
rem
rem Usage: scripts\start-acp.bat
rem Model/key/endpoint come from dataDir\model-config.json (the active profile
rem saved via the web "Add custom model" page); no env vars needed.

set "SELF=start-acp"

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

rem 不在此编译——先运行 scripts\build-backend.bat 生成 target\classes + rpc-cp.txt，再启动。
if not exist "%CP_FILE%" (
  echo [%SELF%] 未找到 %CP_FILE%：请先运行 scripts\build-backend.bat 编译后端。 1>&2
  exit /b 1
)

echo [%SELF%] launching ACP server (model from model-config.json) 1>&2

rem Classpath may exceed 8KB and the .m2 path contains spaces. java @argfile cannot quote-group
rem backslashed Windows paths, so launch via PowerShell + ProcessStartInfo (CreateProcess ~32KB line,
rem CRT parses -cp "..." correctly).
powershell -NoProfile -Command "$cp=[IO.File]::ReadAllText('%CP_FILE%').TrimEnd(); $cp='%ROOT%\dsh-app\target\classes;'+$cp; $q=[string][char]34; $psi=New-Object Diagnostics.ProcessStartInfo; $psi.FileName='%JAVABIN%'; $psi.Arguments='-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dlogback.configurationFile=logback-rpc.xml -cp '+$q+$cp+$q+' com.deepseek.dsh.app.acp.DshAcpServer'; $psi.UseShellExecute=$false; $p=[Diagnostics.Process]::Start($psi); $p.WaitForExit(); exit $p.ExitCode"
exit /b %ERRORLEVEL%
