@echo off
setlocal
cd /d "%~dp0"

set "PY=.venv\Scripts\python.exe"
set "SITE=.venv\Lib\site-packages"
if not exist "%PY%" (
    echo [ERROR] venv not found: backend\.venv
    echo Create it first, then re-run.
    pause
    exit /b 1
)

echo [1/4] Copy CUDA DLLs into ctranslate2 (overwrite)...
copy /Y "%SITE%\nvidia\cublas\bin\cublas64_12.dll" "%SITE%\ctranslate2\" >nul
copy /Y "%SITE%\nvidia\cublas\bin\cublasLt64_12.dll" "%SITE%\ctranslate2\" >nul
copy /Y "%SITE%\nvidia\cuda_runtime\bin\cudart64_12.dll" "%SITE%\ctranslate2\" >nul

echo [2/4] Install PyInstaller...
"%PY%" -m pip install --disable-pip-version-check -q pyinstaller pyinstaller-hooks-contrib
if errorlevel 1 (
    echo [ERROR] pip install failed
    pause
    exit /b 1
)

echo [3/4] Build onedir bundle into .build_onedir\ ...
"%PY%" -m PyInstaller build.spec --noconfirm --clean --distpath ".build_onedir"
if errorlevel 1 (
    echo [ERROR] PyInstaller failed
    pause
    exit /b 1
)
rem onedir 产物是目录（backend.exe + _internal\），不是单文件。部署布局仍是
rem dist\backend.exe + dist\_internal\，与 onefile 时代一致，所以 config.py 的
rem BASE_DIR、服务管理器 YAML、bat 助手都不用改。
rem 数据 data\ tools\ .cache\ 在 dist\ 下、不在构建输出目录内 —— --noconfirm 删输出目录时碰不到 app.db。
if exist "dist\_internal" rmdir /S /Q "dist\_internal"
copy /Y ".build_onedir\backend\backend.exe" "dist\backend.exe" >nul
xcopy /E /I /Y /Q ".build_onedir\backend\_internal" "dist\_internal\" >nul
if not exist "dist\backend.exe" (
    echo [ERROR] deploy failed: dist\backend.exe
    pause
    exit /b 1
)
if not exist "dist\_internal" (
    echo [ERROR] deploy failed: dist\_internal
    pause
    exit /b 1
)
rmdir /S /Q ".build_onedir"
echo   deployed dist\backend.exe + dist\_internal\

echo [4/4] Copy resources...
if not exist "dist\tools" mkdir "dist\tools"
if exist "tools\ffmpeg.exe" (
    copy /Y "tools\ffmpeg.exe" "dist\tools\ffmpeg.exe" >nul
    echo   copied tools\ffmpeg.exe
)
rem 清掉旧 .cache（含 small），用 python 复制 medium 并保留 symlink（robocopy /SL 非提权会失败）
set "MEDIUM=.cache\huggingface\hub\models--Systran--faster-whisper-medium"
if exist "%MEDIUM%" (
    "%PY%" copy_model.py
    if errorlevel 1 (
        echo [ERROR] medium model copy failed
        pause
        exit /b 1
    )
    echo   copied medium model
)

echo.
echo Done: dist\backend.exe
endlocal
