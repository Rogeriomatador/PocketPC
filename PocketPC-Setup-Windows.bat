@echo off
setlocal
title PocketPC Windows Setup

set "REPO=%~dp0"

echo ============================================================
echo PocketPC Windows Setup
echo ============================================================
echo.

if not exist "%REPO%\.git" (
  echo Este arquivo precisa ser executado dentro de um clone do PocketPC.
  echo Pasta atual:
  echo %REPO%
  echo.
  echo Baixe ou clone:
  echo https://github.com/Rogeriomatador/PocketPC
  echo.
  pause
  exit /b 1
)

cd /d "%REPO%"

echo Atualizando o repositorio...
git pull --ff-only
if errorlevel 1 (
  echo.
  echo Falha no git pull. Verifique o Git/rede/autenticacao.
  pause
  exit /b 1
)

echo.
if not exist "%REPO%\scripts\bootstrap-windows.ps1" (
  echo bootstrap-windows.ps1 nao encontrado.
  echo Commit atual:
  git rev-parse HEAD
  pause
  exit /b 1
)

echo Iniciando PocketPC Windows Setup...
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%REPO%\scripts\bootstrap-windows.ps1"
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
