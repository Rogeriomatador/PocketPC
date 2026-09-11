[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$AndroidSdkRoot,
    [string]$OutputDirectory,
    [switch]$DoNotLaunchApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-Adb {
    param([string]$SdkRoot)

    if ($SdkRoot) {
        $candidate = Join-Path $SdkRoot "platform-tools\adb.exe"
        if (Test-Path $candidate -PathType Leaf) {
            return (Resolve-Path $candidate).Path
        }
    }

    foreach ($root in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($root) {
            $candidate = Join-Path $root "platform-tools\adb.exe"
            if (Test-Path $candidate -PathType Leaf) {
                return (Resolve-Path $candidate).Path
            }
        }
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "ADB_NOT_FOUND"
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [string]$Serial,
        [Parameter(Mandatory=$true)][string[]]$Arguments
    )

    $full = @()
    if ($Serial) { $full += @("-s", $Serial) }
    $full += $Arguments

    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Adb @full 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Text = (($output | Out-String).Trim())
    }
}

function Test-Marker {
    param(
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Marker
    )
    return $Text.Contains($Marker, [System.StringComparison]::Ordinal)
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Resolve-Adb $AndroidSdkRoot

$devices = Invoke-Adb $adb $null @("devices")
if ($devices.ExitCode -ne 0) { throw "ADB_DEVICES_FAILED: $($devices.Text)" }

if (-not $DeviceSerial) {
    $online = @(
        ($devices.Text -split "`r?`n") |
            Where-Object { $_ -match '^\S+\s+device$' } |
            ForEach-Object { ($_ -split '\s+')[0] }
    )
    if ($online.Count -ne 1) {
        throw "ADB_DEVICE_SELECTION_REQUIRED:online=$($online.Count)"
    }
    $DeviceSerial = $online[0]
}

$state = Invoke-Adb $adb $DeviceSerial @("get-state")
if ($state.ExitCode -ne 0 -or $state.Text -ne "device") {
    throw "ADB_DEVICE_NOT_READY:$DeviceSerial:$($state.Text)"
}

if (-not $OutputDirectory) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDirectory = Join-Path $repoRoot "local-build\graphics-evidence-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path

$gitCommit = $null
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $rawCommit = & $git.Source -C $repoRoot rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $rawCommit -match '^[0-9a-fA-F]{40}$') {
        $gitCommit = $rawCommit.ToLowerInvariant()
    }
}

$package = "dev.pocketpc.core"
$activity = "dev.pocketpc.core/.MainActivity"

$installed = Invoke-Adb $adb $DeviceSerial @("shell", "pm", "path", $package)
if ($installed.ExitCode -ne 0 -or -not $installed.Text.Contains("package:")) {
    throw "POCKETPC_APP_NOT_INSTALLED"
}

Invoke-Adb $adb $DeviceSerial @("logcat", "-c") | Out-Null

if (-not $DoNotLaunchApp) {
    $launch = Invoke-Adb $adb $DeviceSerial @(
        "shell", "am", "start", "-W", "-n", $activity
    )
    if ($launch.ExitCode -ne 0) {
        throw "POCKETPC_APP_LAUNCH_FAILED:$($launch.Text)"
    }
}

Write-Host ""
Write-Host "PocketPC graphics evidence capture" -ForegroundColor Cyan
Write-Host "Dispositivo: $DeviceSerial"
Write-Host ""
Write-Host "No celular, execute UMA tentativa do runtime Windows/DXVK." -ForegroundColor Yellow
Write-Host "Se o fluxo de Roblox Desktop estiver disponível, use essa tentativa." -ForegroundColor Yellow
Write-Host "Não feche o PocketPC até a tentativa terminar ou falhar." -ForegroundColor Yellow
Write-Host ""
[void](Read-Host "Quando terminar a tentativa, pressione ENTER aqui")

$logcat = Invoke-Adb $adb $DeviceSerial @("logcat", "-d", "-v", "threadtime")
if ($logcat.ExitCode -ne 0) {
    throw "LOGCAT_CAPTURE_FAILED:$($logcat.Text)"
}

$rawLogPath = Join-Path $OutputDirectory "graphics-logcat.txt"
[System.IO.File]::WriteAllText($rawLogPath, $logcat.Text, [System.Text.UTF8Encoding]::new($false))

$markers = [ordered]@{
    pgh1Connected = "POCKETPC_VULKAN_GUEST stage=pgh1_connected_worker_started"
    importReady = "POCKETPC_VULKAN_GUEST stage=resource_import_source_path_ready"
    presentQueueSignalSubmitted = "POCKETPC_VULKAN_GUEST stage=present_queue_signal_submitted"
    exactPresentedImageObserved = "POCKETPC_VULKAN_PRESENT_IMAGE stage=exact_swapchain_image_observed"
    externalOwnershipRoundTrip = "POCKETPC_VULKAN_EXTERNAL_OWNERSHIP stage=roundtrip_completed"
    prePresentCopySubmitted = "POCKETPC_VULKAN_PRESENT_COPY stage=copy_submitted"
    prePresentCopyCompleted = "POCKETPC_VULKAN_PRESENT_COPY stage=copy_queue_completed"
    headlessPresentObserved = "POCKETPC_VULKAN_WSI stage=headless_present_observed"
}

$observed = [ordered]@{}
foreach ($entry in $markers.GetEnumerator()) {
    $observed[$entry.Key] = Test-Marker -Text $logcat.Text -Marker $entry.Value
}

$failureMarkers = @(
    "stage=pgh1_connect_failed",
    "stage=pgt_offer_receive_failed",
    "stage=pvi1_receive_failed",
    "stage=pvi1_import_failed",
    "stage=pvs1_receive_failed",
    "stage=pvs1_import_failed",
    "stage=present_queue_signal_failed",
    "stage=pga_present_queue_signal_ack_failed",
    "POCKETPC_VULKAN_EXTERNAL_OWNERSHIP stage=acquire_failed",
    "POCKETPC_VULKAN_EXTERNAL_OWNERSHIP stage=release_failed",
    "POCKETPC_VULKAN_PRESENT_COPY stage=pre_present_copy_rejected",
    "POCKETPC_VULKAN_PRESENT_COPY stage=copy_cleanup_failed"
)
$observedFailures = @($failureMarkers | Where-Object { Test-Marker -Text $logcat.Text -Marker $_ })

$copyPixelsEvidence = [bool]$observed.prePresentCopyCompleted
$explicitNonClaims = @(
    "Android-visible Vulkan frame",
    "Roblox gameplay validated",
    "stable gameplay session"
)
if (-not $copyPixelsEvidence) {
    $explicitNonClaims = @("swapchain pixels copied") + $explicitNonClaims
}

$evidence = [ordered]@{
    schemaVersion = 3
    classification = "PHYSICAL_EVIDENCE_CAPTURED_NOT_AUTOMATIC_PASS"
    capturedAtUtc = [DateTime]::UtcNow.ToString("o")
    repositoryCommit = $gitCommit
    deviceSerial = $DeviceSerial
    package = $package
    observed = $observed
    derived = [ordered]@{
        exactSwapchainPixelCopyEvidence = $copyPixelsEvidence
        androidVisibleFrameEvidence = $false
        robloxGameplayEvidence = $false
    }
    failureMarkers = $observedFailures
    interpretation = [ordered]@{
        pgh1Connected = "Only proves the live Wine process emitted the PGH1-connected source marker."
        importReady = "Only proves the guest source path reported PGT/PVI1/PVS1 import completion; correlate with host PGA1 evidence before promotion."
        presentQueueSignalSubmitted = "Can support same-Present-queue ordering evidence when emitted by the patched Wine path. It does not prove pixel capture."
        exactPresentedImageObserved = "Proves the exact host swapchain VkImage was identified for that Present. It does not by itself prove a copy."
        externalOwnershipRoundTrip = "Proves one observed guest acquire/release round-trip between VK_QUEUE_FAMILY_EXTERNAL and the exact Wine queue. It does not prove a copied frame."
        prePresentCopySubmitted = "Proves the v51 copy submit was queued after consuming the original Present waits and before the real Present. Submission alone is not completion evidence."
        prePresentCopyCompleted = "With the v51 source contract, this marker is emitted only after the exact-image copy submit and the Present queue reached idle, with the PVI1 destination released back to VK_QUEUE_FAMILY_EXTERNAL/GENERAL. It still does not prove Android displayed the frame."
        headlessPresentObserved = "Headless control-flow evidence only; never a visible-frame proof."
    }
    explicitNonClaims = $explicitNonClaims
    rawLog = $rawLogPath
}

$jsonPath = Join-Path $OutputDirectory "graphics-physical-evidence.json"
$evidence | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8

Write-Host ""
Write-Host "Evidence saved:" -ForegroundColor Green
Write-Host "  $jsonPath"
Write-Host "  $rawLogPath"
Write-Host ""
Write-Host "Observed:" -ForegroundColor Cyan
$observed.GetEnumerator() | ForEach-Object {
    Write-Host ("  {0} = {1}" -f $_.Key, $_.Value)
}
Write-Host ("  exactSwapchainPixelCopyEvidence = {0}" -f $copyPixelsEvidence)
if ($observedFailures.Count -gt 0) {
    Write-Host "Failure markers:" -ForegroundColor Red
    $observedFailures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
}

Write-Host ""
Write-Host "This script captures evidence; it never promotes Android-visible Present or Roblox gameplay by itself." -ForegroundColor Yellow
