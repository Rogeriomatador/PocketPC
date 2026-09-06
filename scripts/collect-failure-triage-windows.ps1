[CmdletBinding()]
param(
    [string]$BuildDir,
    [string]$AndroidSdkRoot,
    [string]$DeviceSerial,
    [string]$FailureStage = "UNKNOWN",
    [string]$FailureMessage = "",
    [string]$OutputDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$packageName = "dev.pocketpc.core"

function Invoke-Capture {
    param(
        [string]$FilePath,
        [string[]]$Arguments = @()
    )

    $output = & $FilePath @Arguments 2>&1
    $exit = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exit
        Text = (($output | Out-String).Trim())
    }
}

function Sha256Text {
    param([string]$Text)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return (
            [BitConverter]::ToString($sha.ComputeHash($bytes))
        ).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Resolve-Sdk {
    param([string]$Explicit)

    $candidates = @(
        $Explicit,
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME
    )

    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }

    foreach (
        $candidate in (
            $candidates |
                Where-Object { $_ } |
                Select-Object -Unique
        )
    ) {
        if (Test-Path $candidate -PathType Container) {
            return (Resolve-Path $candidate).Path
        }
    }

    return $null
}

function Copy-IfPresent {
    param(
        [string]$Source,
        [string]$DestinationName
    )

    if ($Source -and (Test-Path $Source -PathType Leaf)) {
        Copy-Item $Source (Join-Path $OutputDir $DestinationName) -Force
        return $true
    }
    return $false
}

if (-not $OutputDir) {
    if ($BuildDir -and (Test-Path $BuildDir -PathType Container)) {
        $OutputDir = Join-Path $BuildDir "failure-triage"
    }
    else {
        $OutputDir = Join-Path $repoRoot "local-build\failure-triage"
    }
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$OutputDir = (Resolve-Path $OutputDir).Path

$context = [ordered]@{
    schemaVersion = 1
    classification = "FAILURE_TRIAGE_CAPTURED"
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    failureStage = $FailureStage
    failureMessage = $FailureMessage
    repository = [ordered]@{}
    build = [ordered]@{}
    device = [ordered]@{}
    capturedFiles = @()
}

$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $head = Invoke-Capture $git.Source @(
        "-C",
        $repoRoot,
        "rev-parse",
        "HEAD"
    )
    if ($head.ExitCode -eq 0) {
        $context.repository.commit = $head.Text.Trim().ToLowerInvariant()
    }

    $status = Invoke-Capture $git.Source @(
        "-C",
        $repoRoot,
        "status",
        "--porcelain",
        "--untracked-files=all"
    )
    $context.repository.dirty = -not [string]::IsNullOrWhiteSpace(
        $status.Text
    )
}

$buildRoot = $null
if ($BuildDir -and (Test-Path $BuildDir -PathType Container)) {
    $buildRoot = (Resolve-Path $BuildDir).Path
    $context.build.directory = $buildRoot

    $copyMap = [ordered]@{
        "local-build-record.json" = "local-build-record.json"
        "apk-signing.txt" = "apk-signing.txt"
        "device-install\device-install-record.json" = "device-install-record.json"
        "device-install\activity-launch.txt" = "activity-launch.txt"
        "physical-validation\automation-result.json" = "automation-result.json"
        "physical-validation\device-evidence.json" = "device-evidence.json"
        "physical-validation\bundle-verification.txt" = "bundle-verification.txt"
        "physical-validation\device-chain-verification.txt" = "device-chain-verification.txt"
        "physical-validation\physical-validation-record.json" = "physical-validation-record.json"
        "physical-validation\physical-validation-verification.txt" = "physical-validation-verification.txt"
        "physical-validation\first-physical-test-record.json" = "first-physical-test-record.json"
        "physical-validation\first-physical-test-verification.txt" = "first-physical-test-verification.txt"
    }

    $captured = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $copyMap.GetEnumerator()) {
        $source = Join-Path $buildRoot $entry.Key
        if (Copy-IfPresent $source $entry.Value) {
            $captured.Add($entry.Value)
        }
    }

    $preflightBefore = Join-Path (
        Split-Path $buildRoot -Parent
    ) "preflight\preflight-before-build.json"
    $preflightAfter = Join-Path (
        Split-Path $buildRoot -Parent
    ) "preflight\preflight-after-build.json"

    if (Copy-IfPresent $preflightBefore "preflight-before-build.json") {
        $captured.Add("preflight-before-build.json")
    }
    if (Copy-IfPresent $preflightAfter "preflight-after-build.json") {
        $captured.Add("preflight-after-build.json")
    }

    $context.capturedFiles = @($captured)

    $localRecord = Join-Path $buildRoot "local-build-record.json"
    if (Test-Path $localRecord -PathType Leaf) {
        try {
            $record = Get-Content $localRecord -Raw | ConvertFrom-Json
            $context.build.classification = [string]$record.classification
            $context.build.apkFileName = [string]$record.apk.fileName
            $context.build.apkSha256 = [string]$record.apk.sha256
            $context.build.sourceCommit = [string]$record.source.commit
        }
        catch {
            $context.build.recordParseError = $_.Exception.Message
        }
    }
}

$sdkRoot = Resolve-Sdk $AndroidSdkRoot
$adb = $null
if ($sdkRoot) {
    $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (Test-Path $candidate -PathType Leaf) {
        $adb = $candidate
    }
}
if (-not $adb) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $adb = $adbCommand.Source
    }
}

if ($adb) {
    $context.device.adb = "available"
    $devices = Invoke-Capture $adb @("devices", "-l")
    $devices.Text |
        Set-Content (
            Join-Path $OutputDir "adb-devices.txt"
        ) -Encoding UTF8

    $online = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($devices.Text -split '\r?\n')) {
        if ($line -match '^(?<serial>[^\s]+)\s+device(?:\s|$)') {
            $serial = $Matches.serial
            if ($serial -notlike "emulator-*") {
                $online.Add($serial)
            }
        }
    }

    $selected = $null
    if ($DeviceSerial -and $online -contains $DeviceSerial) {
        $selected = $DeviceSerial
    }
    elseif (-not $DeviceSerial -and $online.Count -eq 1) {
        $selected = $online[0]
    }

    if ($selected) {
        $context.device.serialSha256 = Sha256Text $selected

        function Adb-Shell {
            param([string[]]$Arguments)
            return Invoke-Capture $adb (
                @("-s", $selected, "shell") + $Arguments
            )
        }

        $context.device.manufacturer = (
            Adb-Shell @("getprop", "ro.product.manufacturer")
        ).Text.Trim()
        $context.device.model = (
            Adb-Shell @("getprop", "ro.product.model")
        ).Text.Trim()
        $context.device.androidApi = (
            Adb-Shell @("getprop", "ro.build.version.sdk")
        ).Text.Trim()
        $context.device.abis = (
            Adb-Shell @("getprop", "ro.product.cpu.abilist")
        ).Text.Trim()
        $context.device.buildFingerprint = (
            Adb-Shell @("getprop", "ro.build.fingerprint")
        ).Text.Trim()

        $packageDump = Adb-Shell @(
            "dumpsys",
            "package",
            $packageName
        )
        $packageDump.Text |
            Set-Content (
                Join-Path $OutputDir "dumpsys-package.txt"
            ) -Encoding UTF8

        $pid = (Adb-Shell @(
            "pidof",
            $packageName
        )).Text.Trim()

        if ($pid -match '^\d+$') {
            $context.device.pocketPcPid = $pid
            $logcat = Invoke-Capture $adb @(
                "-s",
                $selected,
                "logcat",
                "-d",
                "-t",
                "2000",
                "--pid=$pid"
            )
            $logcat.Text |
                Set-Content (
                    Join-Path $OutputDir "logcat-pocketpc.txt"
                ) -Encoding UTF8
        }
        else {
            $crash = Invoke-Capture $adb @(
                "-s",
                $selected,
                "logcat",
                "-d",
                "-t",
                "500",
                "AndroidRuntime:E",
                "*:S"
            )
            $crash.Text |
                Set-Content (
                    Join-Path $OutputDir "logcat-crash-only.txt"
                ) -Encoding UTF8
        }

        $activity = Adb-Shell @(
            "dumpsys",
            "activity",
            "activities"
        )
        $activity.Text |
            Select-String -Pattern $packageName -Context 3,5 |
            Out-String |
            Set-Content (
                Join-Path $OutputDir "activity-pocketpc.txt"
            ) -Encoding UTF8
    }
    else {
        $context.device.selection = "no-unique-online-physical-device"
    }
}
else {
    $context.device.adb = "unavailable"
}

$hashes = [ordered]@{}
Get-ChildItem $OutputDir -File |
    Where-Object {
        $_.Name -ne "triage-record.json" -and
        $_.Name -ne "triage-record.json.sha256"
    } |
    Sort-Object Name |
    ForEach-Object {
        $hashes[$_.Name] = (
            Get-FileHash $_.FullName -Algorithm SHA256
        ).Hash.ToLowerInvariant()
    }

$context.fileSha256 = $hashes

$recordPath = Join-Path $OutputDir "triage-record.json"
$context |
    ConvertTo-Json -Depth 12 |
    Set-Content $recordPath -Encoding UTF8

$recordSha = (
    Get-FileHash $recordPath -Algorithm SHA256
).Hash.ToLowerInvariant()

"$recordSha  triage-record.json" |
    Set-Content (
        Join-Path $OutputDir "triage-record.json.sha256"
    ) -Encoding ASCII

Write-Host ""
Write-Host "PocketPC failure triage capturado." -ForegroundColor Yellow
Write-Host "Stage  : $FailureStage"
Write-Host "Output : $OutputDir"
Write-Host "Record : $recordPath"
