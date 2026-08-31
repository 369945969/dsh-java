@echo off
chcp 936 >nul
setlocal
rem 编译后端：clean -> install（跳过测试），删除旧 target/ 后全量重建，再生成 rpc-cp.txt。
rem 用法： scripts\build-backend.bat
pushd "%~dp0.." >nul
set "ROOT=%CD%"
popd >nul

echo [build-backend] 清理旧构建产物...
for /d %%D in ("%ROOT%\dsh-*") do if exist "%%D\target" rd /s /q "%%D\target"
if exist "%ROOT%\testcase\target" rd /s /q "%ROOT%\testcase\target"

echo [build-backend] 编译后端（mvn clean install -DskipTests）...
pushd "%ROOT%"
call mvn -q clean install -DskipTests -Dmaven.test.skip=true
set "EXITCODE=%ERRORLEVEL%"
popd
if not "%EXITCODE%"=="0" (
    echo [build-backend] 编译失败 1>&2
    exit /b 1
)
echo [build-backend] 生成运行时 classpath（rpc-cp.txt）...
call mvn -q -f "%ROOT%\pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="%ROOT%\dsh-app\target\rpc-cp.txt"
if errorlevel 1 ( echo [build-backend] 生成 classpath 失败 1>&2 & exit /b 1 )
echo [build-backend] 完成：dsh-app\target\classes + rpc-cp.txt 已生成
exit /b 0
