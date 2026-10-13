@echo off
setlocal EnableExtensions
title PocketPC - Home Test OTA

set "REPO=%~dp0"
cd /d "%REPO%"

echo ============================================================
echo PocketPC - Home Test OTA
echo ============================================================
echo.

if not exist "%REPO%.git" (
  echo ERRO: este arquivo precisa estar dentro do repositorio PocketPC.
  echo Caminho atual: %REPO%
  echo.
  pause
  exit /b 1
)

where git.exe >nul 2>&1
if errorlevel 1 (
  echo ERRO: git.exe nao foi encontrado no PATH.
  echo.
  pause
  exit /b 1
)

echo Atualizando branch Home Test...
git fetch origin improve/alpha22-desktop-continuity
if errorlevel 1 (
  echo.
  echo ERRO: falha no git fetch.
  pause
  exit /b 1
)

git pull --ff-only origin improve/alpha22-desktop-continuity
if errorlevel 1 (
  echo.
  echo ERRO: o git pull nao foi fast-forward.
  echo Verifique se existem alteracoes locais antes de continuar.
  pause
  exit /b 1
)

echo.
echo Iniciando bootstrap OTA com ExecutionPolicy Bypass somente
echo para este processo PowerShell...
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass ^
  -File "%REPO%scripts\bootstrap-home-test-ota-windows-auto.ps1" ^
  -InstallNow

set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo ============================================================
  echo PocketPC Home Test OTA concluido.
  echo ============================================================
) else (
  echo ============================================================
  echo PocketPC Home Test OTA terminou com erro %EXITCODE%.
  echo Copie toda a saida desta janela para continuar o diagnostico.
  echo ============================================================
)
echo.
pause
exit /b %EXITCODE%
