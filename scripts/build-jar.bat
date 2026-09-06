@echo off
chcp 65001 >nul
setlocal
rem Build a self-contained executable fat jar (dsh-app) via spring-boot:repackage.
rem All deps + resources (static frontend, logback, application.yml) are packed inside
rem one jar -- no external classpath/files needed at runtime. Launch via start-jar.bat.
rem
rem Why two steps (verified by experiment):
rem   * The default repackage binding is NOT active for this project's poms, so a plain
rem     "mvn package" leaves a 5MB thin jar (no BOOT-INF/lib). We must call the
rem     spring-boot:repackage goal explicitly.
rem   * Calling "spring-boot:repackage" with -am makes the goal run on every upstream
rem     module too, where it fails ("Unable to find main class" on dsh-core etc.).
rem   * Calling "spring-boot:repackage" alone fails ("Source file is not available,
rem     make sure 'package' runs as part of the same lifecycle").
rem So: (1) install dsh-app + all upstreams into the local repo as thin jars,
rem      (2) on dsh-app alone, run package + spring-boot:repackage in one lifecycle.
rem
rem Usage: scripts\build-jar.bat
rem Output: dsh-app\target\dsh-app-<version>.jar  (run via scripts\start-jar.bat)

set "SELF=build-jar"

pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

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

rem --- Step 1/2: install dsh-app + all upstream modules into the local repo (thin jars).
rem     Upstream jars must be in the local repo so step 2 can resolve them when -pl dsh-app
rem     runs without -am. (If your local repo is already current, this step is idempotent.) ---
echo [%SELF%] 1/2 install dsh-app + upstreams (mvn -pl dsh-app -am clean install)...
call mvn -q -pl dsh-app -am clean install -DskipTests -Dmaven.test.skip=true
if errorlevel 1 ( echo [%SELF%] install step failed 1>&2 & exit /b 1 )

rem --- Step 2/2: package + spring-boot:repackage on dsh-app only. Reactor = {dsh-app},
rem     so the repackage goal runs solely on it (it has the main class); upstream deps
rem     resolve from the local repo populated in step 1. Produces the fat jar + .original. ---
echo [%SELF%] 2/2 repackage dsh-app into fat jar (mvn -pl dsh-app clean package spring-boot:repackage)...
call mvn -q -pl dsh-app clean package spring-boot:repackage -DskipTests -Dmaven.test.skip=true
if errorlevel 1 ( echo [%SELF%] repackage step failed 1>&2 & exit /b 1 )

rem --- Locate fat jar. dsh-app-*.jar matches the fat jar; the thin backup
rem     dsh-app-*.jar.original does not match *.jar (it ends with .original). ---
set "JAR="
for %%F in ("%ROOT%\dsh-app\target\dsh-app-*.jar") do set "JAR=%%~fF"
if not defined JAR (
  echo [%SELF%] fat jar not found under dsh-app\target 1>&2
  exit /b 1
)
echo [%SELF%] done: %JAR%
exit /b 0
