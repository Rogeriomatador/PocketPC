@echo off
setlocal
title PocketPC - Build, Install and Test

set "REPO=%~dp0"
set "JAVA_HOME_PINNED=%LOCALAPPDATA%\PocketPC\toolchains\temurin17"
set "ANDROID_SDK_PINNED=%LOCALAPPDATA%\Android\Sdk"

cd /d "%REPO%"

echo ============================================================
echo PocketPC - Build, Install and Test
echo ============================================================
echo.

if not exist "%REPO%.git" (
  echo Este arquivo precisa ser executado dentro do repositorio PocketPC.
  echo Caminho atual: %REPO%
  echo.
  pause
  exit /b 1
)

echo Atualizando o repositorio...
git pull --ff-only
if errorlevel 1 (
  echo.
  echo Falha no git pull. Verifique a rede e se a arvore Git esta limpa.
  pause
  exit /b 1
)

echo.
echo Conecte e desbloqueie o celular antes de continuar.
echo A Depuracao USB precisa estar autorizada.
echo.
pause

powershell.exe -NoProfile -ExecutionPolicy Bypass ^
  -File "%REPO%scripts\first-physical-test-windows.ps1" ^
  -JavaHome "%JAVA_HOME_PINNED%" ^
  -AndroidSdkRoot "%ANDROID_SDK_PINNED%" ^
  -InstallMissingSdkComponents ^
  -AcceptAndroidLicenses

set "EXITCODE=%ERRORLEVEL%"
echo.
if "%EXITCODE%"=="0" (
  echo ============================================================
  echo PocketPC compilado, instalado, validado e aberto no celular.
  echo ============================================================
) else (
  echo O teste terminou com erro. O triage foi coletado automaticamente.
)
echo.
pause
exit /b %EXITCODE%
