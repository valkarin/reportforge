@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%" >nul

if not exist "%SCRIPT_DIR%mvnw.cmd" (
    echo [ReportForge] mvnw.cmd was not found next to this script.
    popd >nul
    exit /b 1
)

if not defined JAVA_HOME (
    if exist "C:\Users\burak\.jdks\openjdk-21.0.1" (
        set "JAVA_HOME=C:\Users\burak\.jdks\openjdk-21.0.1"
    )
)

if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
    echo [ReportForge] JAVA_HOME is not set. Falling back to Java on PATH.
)

echo [ReportForge] Starting application...
call "%SCRIPT_DIR%mvnw.cmd" javafx:run
set "EXIT_CODE=%ERRORLEVEL%"

popd >nul
exit /b %EXIT_CODE%
