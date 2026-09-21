@echo off
taskkill /IM backend.exe /F >nul 2>&1
echo backend.exe stopped (if it was running).
