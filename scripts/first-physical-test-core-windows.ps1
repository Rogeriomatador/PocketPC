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
if (-not (Test-Path $lockPath -PathType Leaf)) {
    throw "android-build-lock.json ausente."
}

$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
if ($lock.schemaVersion -ne 1 -or $lock.status -ne "PINNED") {
    throw "Toolchain lock não está válido/pinado."
}

$git = (Get-Command git -ErrorAction Stop).Source
$commit = (& $git -C $repoRoot rev-parse HEAD 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Não foi possível resolver o commit Git."
}

$dirty = (& $git -C $repoRoot status --porcelain --untracked-files=all 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "git status falhou."
}
if (-not [string]::IsNullOrWhiteSpace($dirty)) {
    throw (
        "First Physical Test exige árvore Git completamente limpa." +
        [Environment]::NewLine +
        $dirty
    )
}

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot "local-build"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path $OutputRoot).Path

$doctorScript = Join-Path $repoRoot "scripts\doctor-windows.ps1"
$preflightDir = Join-Path $OutputRoot "preflight"
New-Item -ItemType Directory -Force -Path $preflightDir | Out-Null

$doctorArgs = @{
    RequireDevice = $true
    OutputPath = (Join-Path $preflightDir "preflight-before-build.json")
}
if ($AndroidSdkRoot) {
    $doctorArgs.AndroidSdkRoot = $AndroidSdkRoot
}
if ($JavaHome) {
    $doctorArgs.JavaHome = $JavaHome
}
if ($DeviceSerial) {
    $doctorArgs.DeviceSerial = $DeviceSerial
}
if ($InstallMissingSdkComponents) {
    $doctorArgs.AllowMissingSdkComponents = $true
}

Write-Host ""
Write-Host "==> Preflight Doctor (antes do build)" -ForegroundColor Cyan
& $doctorScript @doctorArgs

$appVersion = [string]$lock.app.versionName
$shortCommit = $commit.Substring(0, 12).ToLowerInvariant()
$expectedBuildDir = Join-Path $OutputRoot "$appVersion-$shortCommit"

$buildScript = Join-Path $repoRoot "scripts\build-local-windows.ps1"
$buildArgs = @{
    RequirePythonPolicyChecks = $true
    OutputRoot = $OutputRoot
}
if ($AndroidSdkRoot) {
    $buildArgs.AndroidSdkRoot = $AndroidSdkRoot
}
if ($JavaHome) {
    $buildArgs.JavaHome = $JavaHome
}
if ($InstallMissingSdkComponents) {
    $buildArgs.InstallMissingSdkComponents = $true
}
if ($AcceptAndroidLicenses) {
    $buildArgs.AcceptAndroidLicenses = $true
}

Write-Host ""
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "PocketPC — FIRST PHYSICAL TEST" -ForegroundColor Cyan
Write-Host "Source: $commit"
Write-Host "Version: $appVersion"
Write-Host "====================================================" -ForegroundColor Cyan

Write-Host ""
Write-Host "==> 1/2 Reproducible strict Windows build" -ForegroundColor Cyan
& $buildScript @buildArgs

$buildRecordPath = Join-Path $expectedBuildDir "local-build-record.json"
if (-not (Test-Path $buildRecordPath -PathType Leaf)) {
    throw "Builder não produziu o build directory esperado: $expectedBuildDir"
}

$buildRecord = Get-Content $buildRecordPath -Raw | ConvertFrom-Json
if ($buildRecord.classification -ne "LOCAL_BUILD_POLICY_CHECKED") {
    throw "Build não recebeu LOCAL_BUILD_POLICY_CHECKED."
}
if (
    ([string]$buildRecord.source.commit).ToLowerInvariant() -ne
    $commit.ToLowerInvariant()
) {
    throw "Build record commit divergiu da árvore validada."
}
if ($buildRecord.source.dirty -ne $false) {
    throw "Build record marcou árvore como dirty."
}
if (
    ([string]$buildRecord.source.embeddedRevision).ToLowerInvariant() -ne
    $commit.ToLowerInvariant()
) {
    throw "APK não foi pinado ao commit atual."
}

$doctorAfterArgs = @{
    RequireDevice = $true
    OutputPath = (Join-Path $preflightDir "preflight-after-build.json")
}
if ($AndroidSdkRoot) {
    $doctorAfterArgs.AndroidSdkRoot = $AndroidSdkRoot
}
if ($JavaHome) {
    $doctorAfterArgs.JavaHome = $JavaHome
}
if ($DeviceSerial) {
    $doctorAfterArgs.DeviceSerial = $DeviceSerial
}

Write-Host ""
Write-Host "==> Preflight Doctor (depois do build)" -ForegroundColor Cyan
& $doctorScript @doctorAfterArgs

Write-Host ""
Write-Host "==> 2/2 Automated physical validation" -ForegroundColor Cyan
$validateScript = Join-Path $repoRoot "scripts\validate-device-windows.ps1"
$validateArgs = @{
    BuildDir = $expectedBuildDir
}
if ($AndroidSdkRoot) {
    $validateArgs.AndroidSdkRoot = $AndroidSdkRoot
}
if ($DeviceSerial) {
    $validateArgs.DeviceSerial = $DeviceSerial
}
if ($RequireInstalledApkHash) {
    $validateArgs.RequireInstalledApkHash = $true
}

& $validateScript @validateArgs

$physicalDir = Join-Path $expectedBuildDir "physical-validation"
$physicalRecordPath = Join-Path $physicalDir "physical-validation-record.json"
$physicalVerifyPath = Join-Path $physicalDir "physical-validation-verification.txt"

if (-not (Test-Path $physicalRecordPath -PathType Leaf)) {
    throw "Physical validation record não foi produzido."
}
if (-not (Test-Path $physicalVerifyPath -PathType Leaf)) {
    throw "Final physical verifier output não foi produzido."
}

$physical = Get-Content $physicalRecordPath -Raw | ConvertFrom-Json
$verifyText = Get-Content $physicalVerifyPath -Raw

if ($physical.classification -ne "PHYSICAL_DEVICE_CHAIN_VERIFIED") {
    throw "Physical validation classification não é a esperada."
}
if ($verifyText -notmatch 'PHYSICAL_VALIDATION_RECORD_OK') {
    throw "Physical validation verifier não confirmou PASS."
}
if ($physical.filesystemCriticalPassed -ne $true) {
    throw "Filesystem critical gate não passou."
}
if ($physical.nativeHostLoaded -ne $true) {
    throw "Native Runtime Host não carregou."
}

$finalRecord = [ordered]@{
    schemaVersion = 1
    classification = "POCKETPC_FIRST_PHYSICAL_TEST_VERIFIED"
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    sourceCommit = $commit.ToLowerInvariant()
    appVersion = $appVersion
    localBuildRecordSha256 = (
        Get-FileHash $buildRecordPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    physicalValidationRecordSha256 = (
        Get-FileHash $physicalRecordPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    apkSha256 = [string]$buildRecord.apk.sha256
    evidenceBundleSha256 = [string]$physical.bundleSha256
    filesystemCriticalPassed = [bool]$physical.filesystemCriticalPassed
    nativeHostLoaded = [bool]$physical.nativeHostLoaded
    substrateState = [string]$physical.substrateState
    prootReady = [bool]$physical.prootReady
}

$finalPath = Join-Path $physicalDir "first-physical-test-record.json"
$finalRecord |
    ConvertTo-Json -Depth 10 |
    Set-Content $finalPath -Encoding UTF8

$finalSha = (
    Get-FileHash $finalPath -Algorithm SHA256
).Hash.ToLowerInvariant()

"$finalSha  first-physical-test-record.json" |
    Set-Content (
        Join-Path $physicalDir "first-physical-test-record.json.sha256"
    ) -Encoding ASCII

$python = $null
$pythonArgs = @()
foreach ($candidate in @("python.exe", "python")) {
    $command = Get-Command $candidate -ErrorAction SilentlyContinue
    if ($command) {
        $probe = & $command.Source --version 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0 -and $probe -match 'Python\s+3[.]') {
            $python = $command.Source
            break
        }
    }
}
if (-not $python) {
    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        $probe = & $py.Source -3 --version 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0 -and $probe -match 'Python\s+3[.]') {
            $python = $py.Source
            $pythonArgs = @("-3")
        }
    }
}
if (-not $python) {
    throw "Python 3 desapareceu antes da verificação final."
}

$finalVerifier = Join-Path $repoRoot "scripts\verify-first-physical-test-record.py"
$finalVerifyArgs = @($pythonArgs) + @(
    $finalVerifier,
    $physicalDir,
    "--build-dir",
    $expectedBuildDir,
    "--expected-commit",
    $commit
)
$finalVerifyOutput = & $python @finalVerifyArgs 2>&1 | Out-String

if ($LASTEXITCODE -ne 0 -or $finalVerifyOutput -notmatch 'FIRST_PHYSICAL_TEST_RECORD_OK') {
    throw (
        "Final first-physical-test verifier falhou." +
        [Environment]::NewLine +
        $finalVerifyOutput
    )
}

$finalVerifyOutput |
    Set-Content (
        Join-Path $physicalDir "first-physical-test-verification.txt"
    ) -Encoding UTF8

Write-Host ""
Write-Host "====================================================" -ForegroundColor Green
Write-Host "POCKETPC_FIRST_PHYSICAL_TEST_OK" -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Green
Write-Host "Commit       : $commit"
Write-Host "APK SHA-256  : $($buildRecord.apk.sha256)"
Write-Host "Bundle SHA   : $($physical.bundleSha256)"
Write-Host "Filesystem   : PASS"
Write-Host "Native host  : PASS"
Write-Host "prootReady   : $($physical.prootReady)"
Write-Host "Evidence dir : $physicalDir"
