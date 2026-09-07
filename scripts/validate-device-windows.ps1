[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BuildDir,
    [string]$AndroidSdkRoot,
    [string]$DeviceSerial,
    [switch]$RequireInstalledApkHash,
    [int]$EvidenceTimeoutSeconds = 120,
    [int]$AdbCommandTimeoutSeconds = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($AdbCommandTimeoutSeconds -lt 5 -or $AdbCommandTimeoutSeconds -gt 300) {
    throw "AdbCommandTimeoutSeconds deve ficar entre 5 e 300 segundos."
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [switch]$AllowFailure
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $result = & $FilePath @Arguments 2>&1
        $exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($result | Out-String).Trim()

    if ($exit -ne 0 -and -not $AllowFailure) {
        throw (
            "Command failed ($exit): $FilePath $($Arguments -join ' ')" +
            [Environment]::NewLine +
            $text
        )
    }

    return [pscustomobject]@{
        ExitCode = $exit
        Text = $text
    }
}

$processCaptureScript = Join-Path $PSScriptRoot "process-capture-windows.ps1"
if (-not (Test-Path $processCaptureScript -PathType Leaf)) {
    throw "process-capture-windows.ps1 ausente."
}
. $processCaptureScript

function Invoke-AdbCaptureWithTimeout {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [Parameter(Mandatory=$true)][string]$Operation,
        [Parameter(Mandatory=$true)][int]$TimeoutSeconds,
        [switch]$AllowFailure
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

function Resolve-Python3 {
    foreach ($candidate in @("python.exe", "python")) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command) {
            $probe = Invoke-NativeCapture $command.Source @("--version") -AllowFailure
            if ($probe.ExitCode -eq 0 -and $probe.Text -match 'Python\s+3[.]') {
                return [pscustomobject]@{
                    Executable = $command.Source
                    PrefixArgs = @()
                }
            }
        }
    }

    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        $probe = Invoke-NativeCapture $py.Source @("-3", "--version") -AllowFailure
        if ($probe.ExitCode -eq 0 -and $probe.Text -match 'Python\s+3[.]') {
            return [pscustomobject]@{
                Executable = $py.Source
                PrefixArgs = @("-3")
            }
        }
    }

    throw "Python 3 funcional é obrigatório."
}

function Resolve-Sdk([string]$Explicit) {
    $candidates = @(
        $Explicit,
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME
    )

    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }

    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        if (Test-Path $candidate -PathType Container) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Android SDK não encontrado."
}

function Select-Device {
    param(
        [string]$Adb,
        [string]$RequestedSerial
    )

    if ($RequestedSerial) {
        $state = Invoke-AdbCaptureWithTimeout `
            -Adb $Adb `
            -Arguments @("-s", $RequestedSerial, "get-state") `
            -Operation "adb get-state" `
            -TimeoutSeconds $AdbCommandTimeoutSeconds
        if ($state.Text.Trim() -ne "device") {
            throw "Dispositivo solicitado não está autorizado/online."
        }
        return $RequestedSerial
    }

    $devices = Invoke-AdbCaptureWithTimeout `
        -Adb $Adb `
        -Arguments @("devices") `
        -Operation "adb devices" `
        -TimeoutSeconds $AdbCommandTimeoutSeconds
    $found = New-Object System.Collections.Generic.List[string]

    foreach ($line in ($devices.Text -split '\r?\n')) {
        if ($line -match '^(?<serial>[^\s]+)\s+device$') {
            $serial = $Matches.serial
            if ($serial -like "emulator-*") {
                continue
            }
            $found.Add($serial)
        }
    }

    if ($found.Count -eq 0) {
        throw "Nenhum aparelho físico ADB autorizado encontrado."
    }
    if ($found.Count -gt 1) {
        throw "Mais de um aparelho ADB encontrado; informe -DeviceSerial."
    }

    return $found[0]
}

function Invoke-Python {
    param(
        $PythonInfo,
        [string[]]$Arguments
    )

    return Invoke-NativeCapture $PythonInfo.Executable (
        @($PythonInfo.PrefixArgs) + $Arguments
    )
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildRoot = (Resolve-Path $BuildDir).Path
$buildRecordPath = Join-Path $buildRoot "local-build-record.json"

if (-not (Test-Path $buildRecordPath -PathType Leaf)) {
    throw "local-build-record.json ausente."
}

$buildRecord = Get-Content $buildRecordPath -Raw | ConvertFrom-Json
if ($buildRecord.source.dirty -ne $false) {
    throw "Physical validation exige build limpo/pinado."
}
if ($buildRecord.source.embeddedRevision -ne $buildRecord.source.commit) {
    throw "Build revision não está pinada ao commit."
}

$python = Resolve-Python3
$sdkRoot = Resolve-Sdk $AndroidSdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb -PathType Leaf)) {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "adb.exe não encontrado."
    }
    $adb = $command.Source
}

Write-Host ""
Write-Host "==> ADB Device Selection" -ForegroundColor Cyan
Write-Host "Procurando aparelho autorizado..." -ForegroundColor DarkGray
$serial = Select-Device $adb $DeviceSerial
Write-Host "ADB device selecionado." -ForegroundColor Green

$installScript = Join-Path $repoRoot "scripts\install-device-windows.ps1"
$installArgs = @{
    BuildDir = $buildRoot
    AndroidSdkRoot = $sdkRoot
    DeviceSerial = $serial
    AdbCommandTimeoutSeconds = $AdbCommandTimeoutSeconds
}
if ($RequireInstalledApkHash) {
    $installArgs.RequireInstalledApkHash = $true
}

Write-Host ""
Write-Host "==> Device Install Gate" -ForegroundColor Cyan
& $installScript @installArgs

$installDir = Join-Path $buildRoot "device-install"
$installRecord = Join-Path $installDir "device-install-record.json"
if (-not (Test-Path $installRecord -PathType Leaf)) {
    throw "Device Install Gate não produziu device-install-record.json."
}

$packageName = [string]$buildRecord.app.packageName
$debugActivity = "$packageName/.DebugEvidenceActivity"
$remoteRoot = "/sdcard/Android/data/$packageName/files/automation-evidence"
$remoteResult = "$remoteRoot/automation-result.json"
$remoteBundle = "$remoteRoot/pocketpc-evidence-bundle.zip"
$remoteBundleSha = "$remoteRoot/pocketpc-evidence-bundle.zip.sha256"
$remoteEvidence = "$remoteRoot/device-evidence.json"

Write-Host ""
Write-Host "==> Limpando evidence anterior" -ForegroundColor Cyan
Invoke-AdbCaptureWithTimeout `
    -Adb $adb `
    -Arguments @("-s", $serial, "shell", "rm", "-rf", $remoteRoot) `
    -Operation "adb shell rm previous evidence" `
    -TimeoutSeconds $AdbCommandTimeoutSeconds | Out-Null

Write-Host ""
Write-Host "==> Iniciando Debug Evidence Runner" -ForegroundColor Cyan
$start = Invoke-AdbCaptureWithTimeout `
    -Adb $adb `
    -Arguments @("-s", $serial, "shell", "am", "start", "-W", "-S", "-n", $debugActivity) `
    -Operation "adb shell am start DebugEvidenceActivity" `
    -TimeoutSeconds ([Math]::Max($AdbCommandTimeoutSeconds, 45))
if ($start.Text -notmatch '(?m)^Status:\s*ok\s*$') {
    throw (
        "Debug Evidence Runner não confirmou Status: ok." +
        [Environment]::NewLine +
        $start.Text
    )
}

$deadline = [DateTime]::UtcNow.AddSeconds($EvidenceTimeoutSeconds)
$resultReady = $false

while ([DateTime]::UtcNow -lt $deadline) {
    $probe = Invoke-AdbCaptureWithTimeout `
        -Adb $adb `
        -Arguments @("-s", $serial, "shell", "ls", $remoteResult) `
        -Operation "adb shell ls automation-result" `
        -TimeoutSeconds $AdbCommandTimeoutSeconds `
        -AllowFailure

    if ($probe.ExitCode -eq 0 -and $probe.Text -match 'automation-result[.]json') {
        $resultReady = $true
        break
    }

    Start-Sleep -Milliseconds 750
}

if (-not $resultReady) {
    throw "Debug Evidence Runner não produziu automation-result.json dentro do timeout."
}

$outputDir = Join-Path $buildRoot "physical-validation"
if (Test-Path $outputDir) {
    Remove-Item -Recurse -Force $outputDir
}
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

function Pull-Required {
    param(
        [string]$Remote,
        [string]$Local
    )

    $pull = Invoke-AdbCaptureWithTimeout `
        -Adb $adb `
        -Arguments @("-s", $serial, "pull", $Remote, $Local) `
        -Operation "adb pull required evidence" `
        -TimeoutSeconds ([Math]::Max($AdbCommandTimeoutSeconds, 60))
    if (-not (Test-Path $Local -PathType Leaf)) {
        throw "adb pull não produziu: $Local"
    }
    return $pull
}

Write-Host ""
Write-Host "==> Coletando evidence do aparelho" -ForegroundColor Cyan

$resultPath = Join-Path $outputDir "automation-result.json"
$bundlePath = Join-Path $outputDir "pocketpc-evidence-bundle.zip"
$bundleShaPath = Join-Path $outputDir "pocketpc-evidence-bundle.zip.sha256"
$evidencePath = Join-Path $outputDir "device-evidence.json"

Pull-Required $remoteResult $resultPath | Out-Null

$result = Get-Content $resultPath -Raw | ConvertFrom-Json
if ($result.state -ne "PASS") {
    throw "Debug Evidence Runner retornou FAIL: $($result.errorMessage)"
}
if ($result.sourceRevisionPinned -ne $true) {
    throw "Evidence runner não reportou source revision pinada."
}
if (
    ([string]$result.sourceRevision).ToLowerInvariant() -ne
    ([string]$buildRecord.source.commit).ToLowerInvariant()
) {
    throw "Evidence runner sourceRevision divergiu do build."
}
if ($result.filesystemCriticalPassed -ne $true) {
    throw "Device filesystem critical gate não passou."
}
if ($result.nativeHostLoaded -ne $true) {
    throw "PocketPC Native Runtime Host não carregou no aparelho."
}

Pull-Required $remoteBundle $bundlePath | Out-Null
Pull-Required $remoteBundleSha $bundleShaPath | Out-Null
Pull-Required $remoteEvidence $evidencePath | Out-Null

$bundleHash = (Get-FileHash $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($bundleHash -ne ([string]$result.bundleSha256).ToLowerInvariant()) {
    throw "Bundle SHA-256 divergiu de automation-result.json."
}

$sidecarLine = (Get-Content $bundleShaPath -Raw).Trim()
$sidecarHash = ($sidecarLine -split '\s+')[0].ToLowerInvariant()
if ($sidecarHash -ne $bundleHash) {
    throw "Bundle SHA-256 sidecar divergiu."
}

$expectedCommit = [string]$buildRecord.source.commit

Write-Host ""
Write-Host "==> Verificando Evidence Bundle" -ForegroundColor Cyan
$bundleVerify = Invoke-Python $python @(
    (Join-Path $repoRoot "scripts\verify-device-evidence-bundle.py"),
    $bundlePath,
    "--expected-revision",
    $expectedCommit
)
$bundleVerify.Text |
    Set-Content (Join-Path $outputDir "bundle-verification.txt") -Encoding UTF8

Write-Host ""
Write-Host "==> Verificando cadeia completa" -ForegroundColor Cyan
$chainArgs = @(
    (Join-Path $repoRoot "scripts\verify-device-chain.py"),
    "--build-dir",
    $buildRoot,
    "--install-dir",
    $installDir,
    "--bundle",
    $bundlePath,
    "--expected-commit",
    $expectedCommit,
    "--require-filesystem-pass",
    "--require-native-host"
)
if ($RequireInstalledApkHash) {
    $chainArgs += "--require-installed-apk-hash"
}

$chainVerify = Invoke-Python $python $chainArgs
if ($chainVerify.Text -notmatch 'POCKETPC_DEVICE_CHAIN_OK') {
    throw "Full device-chain verifier não retornou POCKETPC_DEVICE_CHAIN_OK."
}
$chainVerify.Text |
    Set-Content (Join-Path $outputDir "device-chain-verification.txt") -Encoding UTF8

$physicalRecord = [ordered]@{
    schemaVersion = 1
    classification = "PHYSICAL_DEVICE_CHAIN_VERIFIED"
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    sourceCommit = $expectedCommit.ToLowerInvariant()
    buildRecordSha256 = (
        Get-FileHash $buildRecordPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    installRecordSha256 = (
        Get-FileHash $installRecord -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    automationResultSha256 = (
        Get-FileHash $resultPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    evidenceSha256 = (
        Get-FileHash $evidencePath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    bundleSha256 = $bundleHash
    filesystemCriticalPassed = [bool]$result.filesystemCriticalPassed
    nativeHostLoaded = [bool]$result.nativeHostLoaded
    substrateState = [string]$result.substrateState
    prootReady = [bool]$result.prootReady
}

$physicalRecordPath = Join-Path $outputDir "physical-validation-record.json"
$physicalRecord |
    ConvertTo-Json -Depth 10 |
    Set-Content $physicalRecordPath -Encoding UTF8

$physicalRecordHash = (
    Get-FileHash $physicalRecordPath -Algorithm SHA256
).Hash.ToLowerInvariant()

"$physicalRecordHash  physical-validation-record.json" |
    Set-Content (
        Join-Path $outputDir "physical-validation-record.json.sha256"
    ) -Encoding ASCII

Write-Host ""
Write-Host "==> Verificando registro físico final" -ForegroundColor Cyan
$physicalVerify = Invoke-Python $python @(
    (Join-Path $repoRoot "scripts\verify-physical-validation-record.py"),
    $outputDir,
    "--build-dir",
    $buildRoot,
    "--install-dir",
    $installDir,
    "--expected-commit",
    $expectedCommit
)
if ($physicalVerify.Text -notmatch 'PHYSICAL_VALIDATION_RECORD_OK') {
    throw "Final physical-validation verifier não retornou PASS."
}
$physicalVerify.Text |
    Set-Content (
        Join-Path $outputDir "physical-validation-verification.txt"
    ) -Encoding UTF8

Write-Host ""
Write-Host "==> Abrindo PocketPC para teste manual" -ForegroundColor Cyan
$manualLaunch = Invoke-AdbCaptureWithTimeout `
    -Adb $adb `
    -Arguments @("-s", $serial, "shell", "am", "start", "-W", "-S", "-n", "$packageName/.MainActivity") `
    -Operation "adb shell am start MainActivity" `
    -TimeoutSeconds ([Math]::Max($AdbCommandTimeoutSeconds, 45))
if ($manualLaunch.Text -notmatch '(?m)^Status:\s*ok\s*$') {
    throw (
        "PocketPC foi validado, mas a abertura final falhou." +
        [Environment]::NewLine +
        $manualLaunch.Text
    )
}
$manualLaunch.Text |
    Set-Content (Join-Path $outputDir "manual-launch.txt") -Encoding UTF8

Write-Host ""
Write-Host "PocketPC Physical Validation concluída." -ForegroundColor Green
Write-Host "Classification : PHYSICAL_DEVICE_CHAIN_VERIFIED"
Write-Host "Source commit  : $expectedCommit"
Write-Host "Bundle SHA-256 : $bundleHash"
Write-Host "Filesystem     : $($result.filesystemCriticalPassed)"
Write-Host "Native host    : $($result.nativeHostLoaded)"
Write-Host "prootReady     : $($result.prootReady)"
Write-Host "App aberta     : PASS"
Write-Host "Output         : $outputDir"
