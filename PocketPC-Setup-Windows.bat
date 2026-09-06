@echo off
setlocal
title PocketPC Windows Setup Downloader

set "ZIP=%TEMP%\PocketPC-Windows-Setup.zip"
set "DIR=%TEMP%\PocketPC-Windows-Setup"
set "URL=https://raw.githubusercontent.com/Rogeriomatador/PocketPC/main/dist/PocketPC-Windows-Setup.zip"

echo ============================================================
echo PocketPC Windows Setup
echo ============================================================
echo.
echo Baixando o instalador oficial do repositorio PocketPC...
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; if (Test-Path '%DIR%') { Remove-Item '%DIR%' -Recurse -Force }; Invoke-WebRequest -Uri '%URL%' -OutFile '%ZIP%' -UseBasicParsing; Expand-Archive -Path '%ZIP%' -DestinationPath '%DIR%' -Force"

if errorlevel 1 (
    echo.
    echo Falha ao baixar ou extrair o PocketPC Windows Setup.
    pause
    exit /b 1
)

call "%DIR%\INSTALL.bat"
exit /b %ERRORLEVEL%
