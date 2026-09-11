@echo off
setlocal
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\capture-graphics-runtime-evidence-windows.ps1" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo PocketPC graphics physical evidence capture failed with code %RC%.
)

exit /b %RC%
