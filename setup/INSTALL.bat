@echo off
setlocal
title PocketPC Windows Setup
cd /d "%~dp0"

echo ============================================================
echo PocketPC Windows Setup
echo JDK 17 + Android SDK + ADB + componentes pinados
echo ============================================================
echo.
echo Este instalador baixa dependencias oficiais da internet.
echo Pode baixar alguns GB por causa de NDK, CMake e SDK Android.
echo.
pause

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap-windows.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo PocketPC setup concluido.
) else (
  echo PocketPC setup terminou com erro. Veja o texto acima e o log informado.
)
echo.
pause
exit /b %EXITCODE%
