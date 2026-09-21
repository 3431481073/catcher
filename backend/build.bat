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

echo [3/4] Build onefile exe...
"%PY%" -m PyInstaller build.spec --noconfirm --clean
if errorlevel 1 (
    echo [ERROR] PyInstaller failed
    pause
    exit /b 1
)

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
