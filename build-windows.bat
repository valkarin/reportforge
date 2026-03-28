@echo off
setlocal enabledelayedexpansion

echo.
echo ==========================================
echo   ReportForge - Windows Package Script
echo ==========================================
echo.
echo Creates a self-contained Windows app image under dist\ReportForge\
echo that includes its own bundled JRE -- no Java install required to run.
echo.
echo Requirements: JDK 21+ with bin directory in PATH.
echo.

REM ── Prerequisites ─────────────────────────────────────────────────────────────

where jpackage >nul 2>&1
if errorlevel 1 (
    echo ERROR: jpackage not found in PATH.
    echo        Install JDK 21+ and ensure its bin folder is in your PATH.
    echo        Download: https://adoptium.net/
    echo.
    pause
    exit /b 1
)

for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr /r "version"') do (
    echo Java: %%v
    goto :java_done
)
:java_done

REM ── Paths ─────────────────────────────────────────────────────────────────────

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "TARGET=%ROOT%\target"
set "DEPS=%TARGET%\dependency"
set "DIST=%ROOT%\dist"
set "APP_JAR=%TARGET%\reportforge-1.0-SNAPSHOT.jar"

REM ── Step 1: Compile and package ───────────────────────────────────────────────

echo.
echo [1/3] Compiling and packaging...
echo.
call "%ROOT%\mvnw.cmd" clean package -DskipTests
if errorlevel 1 (
    echo.
    echo ERROR: Maven build failed. See output above.
    pause
    exit /b 1
)

if not exist "%APP_JAR%" (
    echo.
    echo ERROR: Expected JAR not found: %APP_JAR%
    pause
    exit /b 1
)

REM ── Step 2: Collect runtime dependencies ──────────────────────────────────────

echo.
echo [2/3] Collecting runtime dependencies...
call "%ROOT%\mvnw.cmd" dependency:copy-dependencies ^
    -DoutputDirectory="%DEPS%" ^
    -DincludeScope=runtime ^
    -q
if errorlevel 1 (
    echo.
    echo ERROR: Failed to collect dependencies.
    pause
    exit /b 1
)
echo Done.

REM ── Step 3: Create Windows app image ──────────────────────────────────────────

echo.
echo [3/3] Creating Windows app image with jpackage...
echo.

if exist "%DIST%\ReportForge" (
    echo Removing previous build...
    rmdir /s /q "%DIST%\ReportForge"
)
if not exist "%DIST%" mkdir "%DIST%"

jpackage ^
    --type app-image ^
    --name "ReportForge" ^
    --app-version "1.0.0" ^
    --description "Test Execution Reporting Tool" ^
    --module-path "%APP_JAR%;%DEPS%" ^
    --module "com.buraktok.reportforge/com.buraktok.reportforge.ReportForgeApplication" ^
    --dest "%DIST%"

if errorlevel 1 (
    echo.
    echo ERROR: jpackage failed. See output above.
    echo.
    echo Troubleshooting tips:
    echo   - Add --verbose to the jpackage call in this script for detailed output.
    echo   - Ensure all module dependencies have an Automatic-Module-Name in their
    echo     MANIFEST.MF if they lack a module-info.class.
    pause
    exit /b 1
)

REM ── Done ──────────────────────────────────────────────────────────────────────

echo.
echo ==========================================
echo   Build complete!
echo.
echo   Output : %DIST%\ReportForge\
echo   Run    : %DIST%\ReportForge\ReportForge.exe
echo ==========================================
echo.

REM ── Optional: create an MSI installer ────────────────────────────────────────
REM To build an .msi installer instead of (or in addition to) the app-image,
REM install WiX Toolset 3.x (https://wixtoolset.org/) and uncomment below.
REM The result will be written to %DIST%\ReportForge-1.0.0.msi
REM
REM jpackage ^
REM     --type msi ^
REM     --name "ReportForge" ^
REM     --app-version "1.0.0" ^
REM     --description "Test Execution Reporting Tool" ^
REM     --module-path "%APP_JAR%;%DEPS%" ^
REM     --module "com.buraktok.reportforge/com.buraktok.reportforge.ReportForgeApplication" ^
REM     --win-menu ^
REM     --win-shortcut ^
REM     --win-dir-chooser ^
REM     --dest "%DIST%"

pause
