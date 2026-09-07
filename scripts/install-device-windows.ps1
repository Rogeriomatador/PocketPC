[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BuildDir,
    [string]$AndroidSdkRoot,
    [string]$DeviceSerial,
    [switch]$AllowEmulator,
    [switch]$AllowUnpinnedBuild,
    [switch]$RequireInstalledApkHash
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-NativeCapture {
    param([string]$FilePath, [string[]]$Arguments = @(), [switch]$AllowFailure)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $FilePath @Arguments 2>&1
        $exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String).Trim()
    if ($exit -ne 0 -and -not $AllowFailure) {
        throw ("Command failed ($exit): $FilePath $($Arguments -join ' ')" + [Environment]::NewLine + $text)
    }
    return [pscustomobject]@{ ExitCode = $exit; Text = $text }
}

function Get-Sha256Text([string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    }
    finally { $sha.Dispose() }
}

function Resolve-Sdk([string]$Explicit) {
    $candidates = @($Explicit, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk") }
    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        if (Test-Path $candidate -PathType Container) { return (Resolve-Path $candidate).Path }
    }
    throw "Android SDK não encontrado."
}

function Select-AdbDevice([string]$Adb, [string]$RequestedSerial, [bool]$EmulatorAllowed) {
    if ($RequestedSerial) {
        $state = Invoke-NativeCapture $Adb @("-s", $RequestedSerial, "get-state")
        if ($state.Text.Trim() -ne "device") { throw "Dispositivo solicitado não está autorizado/online." }
        return $RequestedSerial
    }

    $devices = Invoke-NativeCapture $Adb @("devices")
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($devices.Text -split "`r?`n")) {
        if ($line -match '^(?<serial>[^\s]+)\s+device$') {
            $serial = $Matches.serial
            if (-not $EmulatorAllowed -and $serial -like "emulator-*") { continue }
            $candidates.Add($serial)
        }
    }
    if ($candidates.Count -eq 0) { throw "Nenhum aparelho ADB autorizado encontrado." }
    if ($candidates.Count -gt 1) { throw "Mais de um aparelho ADB encontrado; informe -DeviceSerial." }
    return $candidates[0]
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildRoot = (Resolve-Path $BuildDir).Path
$recordPath = Join-Path $buildRoot "local-build-record.json"

$python = $null
$pythonArgs = @()
foreach ($candidate in @("python.exe", "python")) {
    $command = Get-Command $candidate -ErrorAction SilentlyContinue
    if ($command) {
        $probe = Invoke-NativeCapture $command.Source @("--version") -AllowFailure
        if ($probe.ExitCode -eq 0 -and $probe.Text -match 'Python\s+3[.]') {
            $python = $command.Source
            break
        }
    }
}
if (-not $python) {
    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        $probe = Invoke-NativeCapture $py.Source @("-3", "--version") -AllowFailure
        if ($probe.ExitCode -eq 0 -and $probe.Text -match 'Python\s+3[.]') {
            $python = $py.Source
            $pythonArgs = @("-3")
        }
    }
}
if (-not $python) { throw "Python 3 funcional é obrigatório para o Device Install Gate." }
if (-not (Test-Path $recordPath -PathType Leaf)) { throw "local-build-record.json ausente." }
$record = Get-Content $recordPath -Raw | ConvertFrom-Json

if ($record.source.dirty -eq $true -or $record.source.embeddedRevision -eq "LOCAL_UNPINNED") {
    if (-not $AllowUnpinnedBuild) { throw "Build unpinned/dirty recusado para Device Install Gate." }
}

$apkPath = Join-Path $buildRoot ([string]$record.apk.fileName)
if (-not (Test-Path $apkPath -PathType Leaf)) { throw "APK do build record não existe." }
$apkHash = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkHash -ne ([string]$record.apk.sha256).ToLowerInvariant()) { throw "APK SHA-256 divergiu do build record." }
if ((Get-Item $apkPath).Length -ne [long]$record.apk.bytes) { throw "APK bytes divergiram do build record." }

$verifyBuildScript = Join-Path $repoRoot "scripts\verify-local-build-record.py"
$verifyArgs = $pythonArgs + @(
    $verifyBuildScript,
    $buildRoot,
    "--expected-commit",
    [string]$record.source.commit
)
$verifiedBuild = Invoke-NativeCapture $python $verifyArgs
if ($verifiedBuild.Text -notmatch 'LOCAL_BUILD_RECORD_OK') {
    throw "verify-local-build-record.py não confirmou o build."
}

$sdkRoot = Resolve-Sdk $AndroidSdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb -PathType Leaf)) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $adbCommand) { throw "adb.exe não encontrado." }
    $adb = $adbCommand.Source
}

$serial = Select-AdbDevice $adb $DeviceSerial $AllowEmulator.IsPresent
$serialHash = Get-Sha256Text $serial

function AdbShell([string[]]$Args) {
    return Invoke-NativeCapture $adb (@("-s", $serial, "shell") + $Args)
}

$manufacturer = (AdbShell @("getprop", "ro.product.manufacturer")).Text.Trim()
$model = (AdbShell @("getprop", "ro.product.model")).Text.Trim()
$sdk = [int]((AdbShell @("getprop", "ro.build.version.sdk")).Text.Trim())
$abisText = (AdbShell @("getprop", "ro.product.cpu.abilist")).Text.Trim()
$abis = @($abisText -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$qemu = (AdbShell @("getprop", "ro.kernel.qemu")).Text.Trim()
$fingerprint = (AdbShell @("getprop", "ro.build.fingerprint")).Text.Trim()

if (-not $AllowEmulator -and $qemu -eq "1") { throw "Emulador recusado; use -AllowEmulator se for intencional." }
if ($abis -notcontains "arm64-v8a") { throw "Device Install Gate exige arm64-v8a neste estágio." }

$install = Invoke-NativeCapture $adb @("-s", $serial, "install", "-r", "-t", "--no-incremental", $apkPath)
if ($install.Text -notmatch 'Success') { throw "adb install não retornou Success." }

$packageName = [string]$record.app.packageName
$pmPath = (AdbShell @("pm", "path", $packageName)).Text.Trim()
if ($pmPath -notmatch '^package:(?<path>.+)$') { throw "pm path não confirmou o pacote instalado." }
$remoteApkPath = $Matches.path.Trim()

$dump = (AdbShell @("dumpsys", "package", $packageName)).Text
$versionNameMatch = [regex]::Match($dump, '(?m)^\s*versionName=(?<value>\S+)\s*$')
$versionCodeMatch = [regex]::Match($dump, '(?m)^\s*versionCode=(?<value>\d+)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) { throw "Não foi possível ler versão instalada." }
$installedVersionName = $versionNameMatch.Groups['value'].Value
$installedVersionCode = [long]$versionCodeMatch.Groups['value'].Value
if ($installedVersionName -ne [string]$record.app.versionName) { throw "versionName instalado divergiu." }
if ($installedVersionCode -ne [long]$record.app.versionCode) { throw "versionCode instalado divergiu." }

$launch = AdbShell @("am", "start", "-W", "-S", "-n", "$packageName/.MainActivity")
$launchOk = $launch.Text -match '(?m)^Status:\s*ok\s*$'
if (-not $launchOk) { throw ("Activity launch não confirmou Status: ok." + [Environment]::NewLine + $launch.Text) }

$pidText = (AdbShell @("pidof", $packageName)).Text.Trim()
$appPid = if ($pidText -match '^\d+(\s+\d+)*$') { $pidText } else { $null }

$pullStatus = "UNAVAILABLE"
$installedApkSha256 = $null
$pullError = $null
$tempRoot = Join-Path $env:TEMP "PocketPC-device-install"
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$pulledApk = Join-Path $tempRoot "installed-base-$([Guid]::NewGuid().ToString('N')).apk"
try {
    $pull = Invoke-NativeCapture $adb @("-s", $serial, "pull", $remoteApkPath, $pulledApk) -AllowFailure
    if ($pull.ExitCode -eq 0 -and (Test-Path $pulledApk -PathType Leaf)) {
        $installedApkSha256 = (Get-FileHash $pulledApk -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($installedApkSha256 -eq $apkHash) { $pullStatus = "PASS" } else { $pullStatus = "HASH_MISMATCH" }
    } else { $pullError = $pull.Text }
}
finally { Remove-Item -Force $pulledApk -ErrorAction SilentlyContinue }

if ($pullStatus -eq "HASH_MISMATCH") { throw "APK instalado/pulled divergiu do APK local." }
if ($RequireInstalledApkHash -and $pullStatus -ne "PASS") { throw "Hash do APK instalado era obrigatório, mas adb pull não pôde verificá-lo." }

$classification = if ($pullStatus -eq "PASS") { "DEVICE_INSTALL_APK_HASH_VERIFIED" } else { "DEVICE_INSTALL_METADATA_VERIFIED" }
$outDir = Join-Path $buildRoot "device-install"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$recordOut = Join-Path $outDir "device-install-record.json"
$launchOut = Join-Path $outDir "activity-launch.txt"
$launch.Text | Set-Content $launchOut -Encoding UTF8

$installRecord = [ordered]@{
    schemaVersion = 1
    classification = $classification
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    source = [ordered]@{
        commit = [string]$record.source.commit
        embeddedRevision = [string]$record.source.embeddedRevision
        dirty = [bool]$record.source.dirty
    }
    build = [ordered]@{
        buildRecordSha256 = (Get-FileHash $recordPath -Algorithm SHA256).Hash.ToLowerInvariant()
        apkFileName = [string]$record.apk.fileName
        apkSha256 = $apkHash
        apkBytes = [long]$record.apk.bytes
        packageName = $packageName
        versionName = [string]$record.app.versionName
        versionCode = [long]$record.app.versionCode
    }
    device = [ordered]@{
        serialSha256 = $serialHash
        manufacturer = $manufacturer
        model = $model
        androidApi = $sdk
        abis = @($abis)
        emulator = ($qemu -eq "1")
        buildFingerprint = $fingerprint
    }
    install = [ordered]@{
        adbInstall = "PASS"
        packagePathPresent = $true
        installedVersionName = $installedVersionName
        installedVersionCode = $installedVersionCode
        installedApkHashCheck = $pullStatus
        installedApkSha256 = $installedApkSha256
        pullError = $pullError
    }
    launch = [ordered]@{
        activity = "$packageName/.MainActivity"
        status = "PASS"
        pid = $appPid
        outputFile = "activity-launch.txt"
    }
}

$installRecord | ConvertTo-Json -Depth 10 | Set-Content $recordOut -Encoding UTF8
$recordHash = (Get-FileHash $recordOut -Algorithm SHA256).Hash.ToLowerInvariant()
"$recordHash  device-install-record.json" | Set-Content (Join-Path $outDir "device-install-record.json.sha256") -Encoding ASCII

Write-Host ""
Write-Host "PocketPC Device Install Gate concluído." -ForegroundColor Green
Write-Host "Classification : $classification"
Write-Host "Device         : $manufacturer $model / API $sdk"
Write-Host "Serial SHA-256 : $serialHash"
Write-Host "APK SHA-256    : $apkHash"
Write-Host "Installed hash : $pullStatus"
Write-Host "Record         : $recordOut"
