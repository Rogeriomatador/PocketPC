[CmdletBinding()]
param(
    [string]$AndroidSdkRoot,
    [string]$JavaHome,
    [string]$DeviceSerial,
    [switch]$InstallMissingSdkComponents,
    [switch]$AcceptAndroidLicenses,
    [switch]$RequireInstalledApkHash,
    [string]$OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$lockPath = Join-Path $repoRoot "toolchains\android-build-lock.json"
$lock = Get-Content $lockPath -Raw | ConvertFrom-Json

$git = (Get-Command git -ErrorAction SilentlyContinue)
$commit = $null
if ($git) {
    $resolved = & $git.Source -C $repoRoot rev-parse HEAD 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0 -and $resolved.Trim() -match '^[0-9a-fA-F]{40}$') {
        $commit = $resolved.Trim().ToLowerInvariant()
    }
}

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot "local-build"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path $OutputRoot).Path

$buildDir = $null
if ($commit) {
    $shortCommit = $commit.Substring(0, 12)
    $buildDir = Join-Path $OutputRoot "$($lock.app.versionName)-$shortCommit"
}

$coreScript = Join-Path $repoRoot "scripts\first-physical-test-core-windows.ps1"
$triageScript = Join-Path $repoRoot "scripts\collect-failure-triage-windows.ps1"

$argsMap = @{
    OutputRoot = $OutputRoot
}
if ($AndroidSdkRoot) {
    $argsMap.AndroidSdkRoot = $AndroidSdkRoot
}
if ($JavaHome) {
    $argsMap.JavaHome = $JavaHome
}
if ($DeviceSerial) {
    $argsMap.DeviceSerial = $DeviceSerial
}
if ($InstallMissingSdkComponents) {
    $argsMap.InstallMissingSdkComponents = $true
}
if ($AcceptAndroidLicenses) {
    $argsMap.AcceptAndroidLicenses = $true
}
if ($RequireInstalledApkHash) {
    $argsMap.RequireInstalledApkHash = $true
}

try {
    & $coreScript @argsMap
}
catch {
    $original = $_
    $message = $_.Exception.Message

    Write-Host ""
    Write-Host "PocketPC first physical test falhou." -ForegroundColor Red
    Write-Host "Coletando triage automaticamente..." -ForegroundColor Yellow

    try {
        $triageArgs = @{
            AndroidSdkRoot = $AndroidSdkRoot
            DeviceSerial = $DeviceSerial
            FailureStage = "FIRST_PHYSICAL_TEST"
            FailureMessage = $message
        }

        if ($buildDir -and (Test-Path $buildDir -PathType Container)) {
            $triageArgs.BuildDir = $buildDir
        }
        else {
            $triageArgs.OutputDir = Join-Path $OutputRoot "failure-triage"
        }

        & $triageScript @triageArgs
    }
    catch {
        Write-Warning (
            "O triage automático também falhou: " +
            $_.Exception.Message
        )
    }

    throw $original
}
