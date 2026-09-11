[CmdletBinding()]
param(
    [string]$Adb = "adb",
    [string]$OutputDirectory = "evidence\physical-graphics",
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Package = "dev.pocketpc.core"
$Activity = "dev.pocketpc.core/.MainActivity"
$Tag = "PocketPCGraphics"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $output = & $Adb @Args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB_FAILED: adb $($Args -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

function Get-OneAuthorizedDevice {
    $lines = Invoke-Adb devices
    $devices = @(
        $lines |
            Select-Object -Skip 1 |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match "\sdevice$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )
    if ($devices.Count -ne 1) {
        throw "ADB_DEVICE_SELECTION_REQUIRED: expected exactly one authorized device, found $($devices.Count)."
    }
    return $devices[0]
}

function Get-LocalHead {
    try {
        $value = (& git rev-parse HEAD 2>$null).Trim()
        if ($LASTEXITCODE -eq 0 -and $value -match "^[0-9a-fA-F]{40}$") {
            return $value.ToLowerInvariant()
        }
    } catch {}
    return $null
}

function Has-Marker {
    param([string[]]$Lines, [string]$Marker)
    return [bool]($Lines | Where-Object { $_ -like "*$Marker*" } | Select-Object -First 1)
}

function Marker-State {
    param([bool]$Observed)
    if ($Observed) { return "OBSERVED" }
    return "NOT_OBSERVED"
}

Write-Host "============================================================"
Write-Host "PocketPC - Physical Graphics Gates (Windows + ADB)"
Write-Host "============================================================"

$serial = Get-OneAuthorizedDevice
Write-Host "ADB device: $serial"

$packagePath = Invoke-Adb shell pm path $Package
if (-not ($packagePath | Where-Object { $_ -like "package:*" })) {
    throw "POCKETPC_PACKAGE_NOT_INSTALLED:$Package"
}

$model = ((Invoke-Adb shell getprop ro.product.model) -join "").Trim()
$manufacturer = ((Invoke-Adb shell getprop ro.product.manufacturer) -join "").Trim()
$api = ((Invoke-Adb shell getprop ro.build.version.sdk) -join "").Trim()
$abi = ((Invoke-Adb shell getprop ro.product.cpu.abi) -join "").Trim()
$packageDump = Invoke-Adb shell dumpsys package $Package
$versionName = (($packageDump | Where-Object { $_ -match "versionName=" } | Select-Object -First 1) -replace ".*versionName=", "").Trim()
$versionCodeLine = ($packageDump | Where-Object { $_ -match "versionCode=" } | Select-Object -First 1)
$versionCode = if ($versionCodeLine) { (($versionCodeLine -replace ".*versionCode=", "") -split "\s+")[0] } else { $null }
$localHead = Get-LocalHead

Write-Host "Device: $manufacturer $model | API $api | ABI $abi"
Write-Host "Installed PocketPC: $versionName ($versionCode)"
if ($localHead) { Write-Host "Local repository HEAD: $localHead" }

# Only logcat is cleared. App data/storage are intentionally untouched.
Invoke-Adb logcat -c | Out-Null

if (-not $NoLaunch) {
    Invoke-Adb shell am start -n $Activity | Out-Null
    Write-Host "PocketPC opened on the device."
}

Write-Host ""
Write-Host "No PocketPC result is assumed yet."
Write-Host "On the phone, open the Runtime/Windows test path and perform ONE controlled Windows attempt."
Write-Host "If you are specifically testing Roblox, start it only through the PocketPC controlled path."
Write-Host "When the attempt has finished or stopped, return here."
Read-Host "Press Enter to collect the evidence"

$rawLog = Invoke-Adb logcat -d -v threadtime
$graphicsLines = @($rawLog | Where-Object { $_ -like "*$Tag*" -and $_ -like "*POCKETPC_GRAPHICS_EVIDENCE*" })

$authenticated = Has-Marker $graphicsLines "PGH1_AUTHENTICATED"
$offered = Has-Marker $graphicsLines "RESOURCE_OFFERED"
$imported = Has-Marker $graphicsLines "GUEST_IMPORT_CONFIRMED"
$presentQueueSignal = Has-Marker $graphicsLines "PRESENT_QUEUE_SIGNAL_OBSERVED"
$blockedLines = @($graphicsLines | Where-Object { $_ -like "* BLOCKED blocker=*" })

$queueFamily = $null
$queueLine = $graphicsLines | Where-Object { $_ -like "*PRESENT_QUEUE_SIGNAL_OBSERVED*" } | Select-Object -Last 1
if ($queueLine -and $queueLine -match "queue_family=([0-9]+)") {
    $queueFamily = [int]$Matches[1]
}

$resourceId = $null
$generation = $null
$identityLine = $graphicsLines | Where-Object { $_ -match "resource_id=[0-9]+" } | Select-Object -Last 1
if ($identityLine) {
    if ($identityLine -match "resource_id=([0-9]+)") { $resourceId = $Matches[1] }
    if ($identityLine -match "generation=([0-9]+)") { $generation = $Matches[1] }
}

$highestGate =
    if ($presentQueueSignal) { "PRESENT_QUEUE_SIGNAL_OBSERVED" }
    elseif ($imported) { "GUEST_IMPORT_CONFIRMED" }
    elseif ($offered) { "RESOURCE_OFFERED" }
    elseif ($authenticated) { "PGH1_AUTHENTICATED" }
    else { "NONE" }

$classification =
    if ($presentQueueSignal) {
        "PHYSICAL_PARTIAL_PRESENT_QUEUE_ORDERING_OBSERVED_VISIBLE_FRAME_NOT_PROVEN"
    } elseif ($imported) {
        "PHYSICAL_PARTIAL_GUEST_IMPORT_OBSERVED_PRESENT_QUEUE_ORDERING_NOT_OBSERVED"
    } elseif ($offered) {
        "PHYSICAL_PARTIAL_HOST_RESOURCE_OFFER_OBSERVED_GUEST_IMPORT_NOT_OBSERVED"
    } elseif ($authenticated) {
        "PHYSICAL_PARTIAL_PGH1_AUTHENTICATED_RESOURCE_OFFER_NOT_OBSERVED"
    } else {
        "PHYSICAL_GRAPHICS_GATES_NOT_OBSERVED"
    }

$timestamp = [DateTimeOffset]::Now
$evidence = [ordered]@{
    schemaVersion = 1
    classification = $classification
    capturedAt = $timestamp.ToString("o")
    device = [ordered]@{
        serial = $serial
        manufacturer = $manufacturer
        model = $model
        api = $api
        abi = $abi
    }
    pocketPc = [ordered]@{
        package = $Package
        installedVersionName = $versionName
        installedVersionCode = $versionCode
        localRepositoryHead = $localHead
    }
    gates = [ordered]@{
        pgh1Authenticated = Marker-State $authenticated
        hostResourceOffered = Marker-State $offered
        guestImportConfirmed = Marker-State $imported
        samePresentQueueTimelineSignal = Marker-State $presentQueueSignal
        exactPresentedImageIdentity = "NOT_OBSERVED_BY_THIS_LOGCAT_CHANNEL"
        swapchainPixelCopy = "NOT_IMPLEMENTED"
        hostVisibleFrame = "NOT_IMPLEMENTED"
        robloxGameplay = "NOT_INFERRED"
    }
    highestObservedGate = $highestGate
    resource = [ordered]@{
        resourceId = $resourceId
        generation = $generation
        queueFamilyIndex = $queueFamily
    }
    blockers = @($blockedLines)
    rawGraphicsEvidenceLines = @($graphicsLines)
    limits = @(
        "OBSERVED means an Android host marker was physically collected from this installed app run.",
        "NOT_OBSERVED is not equivalent to a code failure.",
        "PRESENT_QUEUE_SIGNAL_OBSERVED does not prove swapchain pixels were copied.",
        "This collector does not infer Roblox gameplay from generic Wine/DXVK activity."
    )
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$fileName = "graphics-physical-{0}.json" -f $timestamp.ToString("yyyyMMdd-HHmmss")
$outputPath = Join-Path $OutputDirectory $fileName
$evidence | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $outputPath

Write-Host ""
Write-Host "================ GRAPHICS EVIDENCE ================"
Write-Host "PGH1 authenticated:                 $(Marker-State $authenticated)"
Write-Host "Host resource offered:              $(Marker-State $offered)"
Write-Host "Guest Vulkan import confirmed:      $(Marker-State $imported)"
Write-Host "Same-Present-queue PVS1 signal:     $(Marker-State $presentQueueSignal)"
Write-Host "Swapchain pixel copy:               NOT_IMPLEMENTED"
Write-Host "Host-visible frame:                 NOT_IMPLEMENTED"
Write-Host "Classification: $classification"
Write-Host "Evidence: $outputPath"
Write-Host "====================================================="n
if ($blockedLines.Count -gt 0) {
    Write-Host "Observed blockers:"
    $blockedLines | ForEach-Object { Write-Host "  $_" }
}
