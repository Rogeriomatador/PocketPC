@echo off
setlocal EnableExtensions
title PocketPC - Publish OTA Only

set "REPO=%~dp0"
cd /d "%REPO%"

echo ============================================================
echo PocketPC - Publish OTA Only
echo ============================================================
echo Este fluxo publica uma nova versao OTA, mas NAO instala por ADB.
echo O objetivo e deixar o PocketPC do celular detectar e instalar sozinho.
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
echo Iniciando build, testes, assinatura e publicacao OTA...
echo Nenhum adb install sera executado neste fluxo.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass ^
  -File "%REPO%scripts\bootstrap-home-test-ota-windows-auto.ps1"

set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo ============================================================
  echo PocketPC OTA publicado SEM instalacao por ADB.
  echo Agora feche e abra o PocketPC no celular para testar o OTA.
  echo ============================================================
) else (
  echo ============================================================
  echo Publicacao OTA terminou com erro %EXITCODE%.
  echo Copie toda a saida desta janela para continuar o diagnostico.
  echo ============================================================
)
echo.
pause
exit /b %EXITCODE%
