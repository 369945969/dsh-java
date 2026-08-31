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

rem recompile backend before launch (clean install picks up any .java/pom change), then refresh classpath
echo [%SELF%] recompiling backend (mvn clean install)... 1>&2
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true || ( echo [%SELF%] mvn clean install failed 1>&2 & exit /b 1 )
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%CP_FILE%" || ( echo [%SELF%] mvn build-classpath failed 1>&2 & exit /b 1 )

echo [%SELF%] launching CLI REPL (model from model-config.json) 1>&2

rem 用 PowerShell 写 argfile（正确引用含空格的 classpath），再用 java @argfile 直接启动。
rem 不经 PowerShell Process.Start 启动 java，使 java 直接继承 cmd 控制台 → System.console()
rem 非 null，readLine 走 ReadConsoleW 宽字符 API，正确读取中文（与控制台代码页无关）。
set "ARGF=%ROOT%\dsh-app\target\dsh-cli-%RANDOM%.arg"
powershell -NoProfile -Command "$cp='%ROOT%\dsh-app\target\classes;'+[IO.File]::ReadAllText('%CP_FILE%').TrimEnd(); $cp=$cp.Replace('\','\\'); $n=[char]10; $q=[char]34; [IO.File]::WriteAllText('%ARGF%', '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dlogback.configurationFile=logback-cli.xml'+$n+'-cp'+$n+$q+$cp+$q+$n+'com.deepseek.dsh.app.cli.DshRepl'+$n)"
if errorlevel 1 ( echo [%SELF%] failed to write argfile 1>&2 & exit /b 1 )
chcp 65001 >nul
"%JAVABIN%" @%ARGF%
set "EXITCODE=%ERRORLEVEL%"
if exist "%ARGF%" del "%ARGF%" 2>nul
exit /b %EXITCODE%
