[CmdletBinding()]
param(
    [string]$AndroidSdkRoot,
    [string]$DeviceSerial,
    [int]$EvidenceTimeoutSeconds = 120,
    [int]$AdbCommandTimeoutSeconds = 20,
    [string]$OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$processCaptureScript = Join-Path $PSScriptRoot "process-capture-windows.ps1"
if (-not (Test-Path $processCaptureScript -PathType Leaf)) {
    throw "process-capture-windows.ps1 is missing."
}
. $processCaptureScript

function Resolve-Sdk([string]$Explicit) {
    $candidates = @($Explicit, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }

    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        if (Test-Path $candidate -PathType Container) {
            return (Resolve-Path $candidate).Path
        }
    }
    throw "Android SDK not found."
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [Parameter(Mandatory=$true)][string]$Operation,
        [switch]$AllowFailure,
        [int]$TimeoutSeconds = 20
    )

    $captureArgs = @{
        FilePath = $Adb
        Arguments = $Arguments
        Operation = $Operation
        TimeoutSeconds = $TimeoutSeconds
    }
    if ($AllowFailure) {
        $captureArgs.AllowFailure = $true
    }
    return Invoke-PocketPcProcessCapture @captureArgs
}

function Select-Device([string]$Adb, [string]$RequestedSerial) {
    if ($RequestedSerial) {
        $stateArgs = @{
            Adb = $Adb
            Arguments = @("-s", $RequestedSerial, "get-state")
            Operation = "adb get-state"
            TimeoutSeconds = $AdbCommandTimeoutSeconds
        }
        $state = Invoke-Adb @stateArgs
        if ($state.Text.Trim() -ne "device") {
            throw "Requested device is not online/authorized."
        }
        return $RequestedSerial
    }

    $devicesArgs = @{
        Adb = $Adb
        Arguments = @("devices")
        Operation = "adb devices"
        TimeoutSeconds = $AdbCommandTimeoutSeconds
    }
    $devices = Invoke-Adb @devicesArgs

    $found = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($devices.Text -split "\r?\n")) {
        if ($line -match '^(?<serial>[^\s]+)\s+device$') {
            $serial = $Matches.serial
            if ($serial -like "emulator-*") {
                continue
            }
            $found.Add($serial)
        }
    }

    if ($found.Count -eq 0) {
        throw "No authorized physical Android device found."
    }
    if ($found.Count -gt 1) {
        throw "More than one device found. Pass -DeviceSerial."
    }
    return $found[0]
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot "local-build\diagnostics"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$sdkRoot = Resolve-Sdk $AndroidSdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb -PathType Leaf)) {
    throw "adb.exe not found."
}

Write-Host ""
Write-Host "PocketPC filesystem diagnostic" -ForegroundColor Cyan
Write-Host "Classification : DIAGNOSTIC_ONLY_NOT_A_GATE"
Write-Host "This command does not create or upgrade PHYSICAL evidence."

$serial = Select-Device $adb $DeviceSerial
$packageName = "dev.pocketpc.core"
$activity = "$packageName/.DebugEvidenceActivity"
$remoteRoot = "/sdcard/Android/data/$packageName/files/automation-evidence"
$remoteResult = "$remoteRoot/automation-result.json"
$remoteEvidence = "$remoteRoot/device-evidence.json"

$packageArgs = @{
    Adb = $adb
    Arguments = @("-s", $serial, "shell", "pm", "path", $packageName)
    Operation = "adb shell pm path PocketPC"
    TimeoutSeconds = $AdbCommandTimeoutSeconds
}
$packageProbe = Invoke-Adb @packageArgs
if ($packageProbe.Text -notmatch '^package:') {
    throw "PocketPC debug APK is not installed."
}

$clearArgs = @{
    Adb = $adb
    Arguments = @("-s", $serial, "shell", "rm", "-rf", $remoteRoot)
    Operation = "adb shell rm prior diagnostic evidence"
    TimeoutSeconds = $AdbCommandTimeoutSeconds
}
Invoke-Adb @clearArgs | Out-Null

Write-Host ""
Write-Host "Starting DebugEvidenceActivity..." -ForegroundColor Cyan
$startArgs = @{
    Adb = $adb
    Arguments = @("-s", $serial, "shell", "am", "start", "-W", "-S", "-n", $activity)
    Operation = "adb shell am start DebugEvidenceActivity"
    TimeoutSeconds = [Math]::Max($AdbCommandTimeoutSeconds, 45)
}
$start = Invoke-Adb @startArgs
if ($start.Text -notmatch '(?m)^Status:\s*ok\s*$') {
    throw ("DebugEvidenceActivity did not report Status: ok." + [Environment]::NewLine + $start.Text)
}

$deadline = [DateTime]::UtcNow.AddSeconds($EvidenceTimeoutSeconds)
$ready = $false
while ([DateTime]::UtcNow -lt $deadline) {
    $probeArgs = @{
        Adb = $adb
        Arguments = @("-s", $serial, "shell", "ls", $remoteResult)
        Operation = "adb shell ls automation-result"
        TimeoutSeconds = $AdbCommandTimeoutSeconds
        AllowFailure = $true
    }
    $probe = Invoke-Adb @probeArgs
    if ($probe.ExitCode -eq 0 -and $probe.Text -match 'automation-result[.]json') {
        $ready = $true
        break
    }
    Start-Sleep -Milliseconds 750
}
if (-not $ready) {
    throw "Debug evidence did not finish inside the timeout."
}

$stamp = [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss")
$outputDir = Join-Path $OutputRoot "filesystem-$stamp"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$resultPath = Join-Path $outputDir "automation-result.json"
$evidencePath = Join-Path $outputDir "device-evidence.json"

$resultPullArgs = @{
    Adb = $adb
    Arguments = @("-s", $serial, "pull", $remoteResult, $resultPath)
    Operation = "adb pull automation-result"
    TimeoutSeconds = 60
}
Invoke-Adb @resultPullArgs | Out-Null

$evidencePullArgs = @{
    Adb = $adb
    Arguments = @("-s", $serial, "pull", $remoteEvidence, $evidencePath)
    Operation = "adb pull device-evidence"
    TimeoutSeconds = 60
}
Invoke-Adb @evidencePullArgs | Out-Null

$result = Get-Content $resultPath -Raw | ConvertFrom-Json
if ($result.state -ne "PASS") {
    throw ("DebugEvidenceActivity returned FAIL: " + [string]$result.errorMessage)
}

$evidence = Get-Content $evidencePath -Raw | ConvertFrom-Json
$checks = @(
    @("relativeSymlink", "Relative symlink"),
    @("absoluteSymlink", "Absolute symlink"),
    @("hardlink", "Hardlink"),
    @("noFollowCleanup", "NOFOLLOW cleanup"),
    @("externalTargetPreserved", "External target preserved")
)

Write-Host ""
Write-Host "Filesystem capabilities" -ForegroundColor Cyan
foreach ($check in $checks) {
    $propertyName = [string]$check[0]
    $label = [string]$check[1]
    $capability = $evidence.filesystem.$propertyName
    if ($null -eq $capability) {
        Write-Host ("[UNKNOWN] {0}: missing evidence" -f $label) -ForegroundColor Yellow
        continue
    }

    $passed = ($capability.passed -eq $true)
    $state = if ($passed) { "PASS" } else { "FAIL" }
    $color = if ($passed) { "Green" } else { "Red" }
    Write-Host (
        "[{0}] {1}: {2}" -f $state, $label, [string]$capability.detail
    ) -ForegroundColor $color
}

$overall = if ($evidence.filesystem.allCriticalPassed -eq $true) { "PASS" } else { "FAIL" }
Write-Host ""
Write-Host ("Filesystem critical result : {0}" -f $overall)
Write-Host ("Output                     : {0}" -f $outputDir)
Write-Host "Classification              : DIAGNOSTIC_ONLY_NOT_A_GATE"
