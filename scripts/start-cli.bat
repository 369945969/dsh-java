@echo off
chcp 65001 >nul
setlocal
rem Launch the CLI interactive terminal (REPL) - the original Harness default interactive mode.
rem Reads user input line-by-line from stdin, drives the agent conversation, prints replies to stdout.
rem Supports /exit, /new, /tokens; session memory persists across turns.
rem
rem Usage: scripts\start-cli.bat
rem Model/key/endpoint come from dataDir\model-config.json (the active profile
rem saved via the web "Add custom model" page); no env vars needed.

set "SELF=start-cli"

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

rem No build here: run scripts\build-backend.bat first to produce target\classes + rpc-cp.txt, then launch.
if not exist "%CP_FILE%" (
  echo [%SELF%] %CP_FILE% not found: run scripts\build-backend.bat to build the backend first. 1>&2
  exit /b 1
)

echo [%SELF%] launching CLI REPL (model from model-config.json) 1>&2

rem Write an argfile via PowerShell (correctly quotes the spaces in the classpath), then launch java @argfile.
rem Do NOT launch java through PowerShell Process.Start: java inherits the cmd console directly, so
rem System.console() is non-null and readLine uses ReadConsoleW wide-char API (reads CJK correctly
rem regardless of the console code page).
set "ARGF=%ROOT%\dsh-app\target\dsh-cli-%RANDOM%.arg"
powershell -NoProfile -Command "$cp='%ROOT%\dsh-app\target\classes;'+[IO.File]::ReadAllText('%CP_FILE%').TrimEnd(); $cp=$cp.Replace('\','\\'); $n=[char]10; $q=[char]34; [IO.File]::WriteAllText('%ARGF%', '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dlogback.configurationFile=logback-cli.xml'+$n+'-cp'+$n+$q+$cp+$q+$n+'com.deepseek.dsh.app.cli.DshRepl'+$n)"
if errorlevel 1 ( echo [%SELF%] failed to write argfile 1>&2 & exit /b 1 )
chcp 65001 >nul
"%JAVABIN%" @%ARGF%
set "EXITCODE=%ERRORLEVEL%"
if exist "%ARGF%" del "%ARGF%" 2>nul
exit /b %EXITCODE%
