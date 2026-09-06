@echo off
setlocal
title PocketPC Windows Setup

set "REPO=D:\Projetos\PocketPC"

echo ============================================================
echo PocketPC Windows Setup
echo ============================================================
echo.

if not exist "%REPO%\.git" (
  echo Repositorio PocketPC nao encontrado em:
  echo %REPO%
  echo.
  echo Clone primeiro:
  echo git clone https://github.com/Rogeriomatador/PocketPC.git D:\Projetos\PocketPC
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
