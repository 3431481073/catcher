@echo off
cd /d "%~dp0"
start /wait "" "dist\backend.exe" --status
if %errorlevel%==0 (echo backend running) else (echo backend not running)
