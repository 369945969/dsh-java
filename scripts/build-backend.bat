@echo off
chcp 65001 >nul
setlocal
rem Build backend: clean -> install (skip tests), remove old target/ then full rebuild, then generate rpc-cp.txt.
rem Usage: scripts\build-backend.bat
pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

echo [build-backend] cleaning old build artifacts...
for /d %%D in ("%ROOT%\dsh-*") do if exist "%%D\target" rd /s /q "%%D\target"
if exist "%ROOT%\testcase\target" rd /s /q "%ROOT%\testcase\target"

echo [build-backend] building backend (mvn clean install -DskipTests)...
pushd "%ROOT%"
call mvn -q clean install -DskipTests -Dmaven.test.skip=true
set "EXITCODE=%ERRORLEVEL%"
popd
if not "%EXITCODE%"=="0" (
    echo [build-backend] build failed 1>&2
    exit /b 1
)
echo [build-backend] generating runtime classpath (rpc-cp.txt)...
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%ROOT%\dsh-app\target\rpc-cp.txt"
if errorlevel 1 ( echo [build-backend] generate classpath failed 1>&2 & exit /b 1 )
echo [build-backend] done: dsh-app\target\classes + rpc-cp.txt ready
exit /b 0
