@echo off
cd /d "%~dp0"
if not exist "dist\backend.exe" (
    echo [ERROR] dist\backend.exe not found. Run build.bat first.
    pause
    exit /b 1
)
start "" "dist\backend.exe"
