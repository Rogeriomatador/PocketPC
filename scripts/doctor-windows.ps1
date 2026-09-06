[CmdletBinding()]
param(
    [string]$AndroidSdkRoot,
    [string]$JavaHome,
    [string]$DeviceSerial,
    [switch]$RequireDevice,
    [switch]$AllowMissingSdkComponents,
    [switch]$CheckNetwork,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$lockPath = Join-Path $repoRoot "toolchains\android-build-lock.json"

$checks = New-Object System.Collections.Generic.List[object]
$context = [ordered]@{}

function Add-Check {
    param(
        [string]$Id,
        [ValidateSet("PASS", "WARN", "FAIL")][string]$Status,
        [string]$Detail
    )

    $checks.Add(
        [pscustomobject]@{
            id = $Id
            status = $Status
            detail = $Detail
        }
    )
}

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

function Resolve-Python3 {
    foreach ($candidate in @("python.exe", "python")) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command) {
            $probe = Invoke-Capture $command.Source @("--version")
            if ($probe.ExitCode -eq 0 -and $probe.Text -match 'Python\s+3[.]') {
                return [pscustomobject]@{
                    Executable = $command.Source
                    PrefixArgs = @()
                    Version = $probe.Text
                }
            }
        }
    }

    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        $probe = Invoke-Capture $py.Source @("-3", "--version")
        if ($probe.ExitCode -eq 0 -and $probe.Text -match 'Python\s+3[.]') {
            return [pscustomobject]@{
                Executable = $py.Source
                PrefixArgs = @("-3")
                Version = $probe.Text
            }
        }
    }

    return $null
}

function Resolve-Java {
    param(
        [string]$Explicit,
        [int]$RequiredMajor
    )

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Explicit) { $candidates.Add($Explicit) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    if ($env:ProgramFiles) {
        $candidates.Add(
            (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
        )
    }
    if ($env:LOCALAPPDATA) {
        $candidates.Add(
            (Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr")
        )
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $java = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path $java -PathType Leaf)) {
            continue
        }

        $probe = Invoke-Capture $java @("-version")
        if (
            ($probe.Text -match 'version\s+"(?<major>\d+)') -and
            ([int]$Matches.major -eq $RequiredMajor)
        ) {
            return [pscustomobject]@{
                Home = (Resolve-Path $candidate).Path
                Executable = (Resolve-Path $java).Path
                Version = $probe.Text
            }
        }
    }

    return $null
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

function Get-FreeBytesForPath {
    param([string]$Path)

    $root = [IO.Path]::GetPathRoot((Resolve-Path $Path).Path)
    if (-not $root) {
        return $null
    }

    $driveName = $root.TrimEnd("\").TrimEnd(":")
    $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
    if (-not $drive) {
        return $null
    }
    return [long]$drive.Free
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

if (-not (Test-Path $lockPath -PathType Leaf)) {
    Add-Check "toolchain-lock" "FAIL" "android-build-lock.json ausente."
    $lock = $null
}
else {
    try {
        $lock = Get-Content $lockPath -Raw | ConvertFrom-Json
        if (
            ($lock.schemaVersion -eq 1) -and
            ($lock.status -eq "PINNED")
        ) {
            Add-Check "toolchain-lock" "PASS" "Toolchain lock PINNED schema v1."
        }
        else {
            Add-Check "toolchain-lock" "FAIL" "Toolchain lock inválido/não PINNED."
        }
    }
    catch {
        $lock = $null
        Add-Check "toolchain-lock" "FAIL" "Toolchain lock JSON inválido: $($_.Exception.Message)"
    }
}

$gitCommand = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCommand) {
    Add-Check "git" "FAIL" "git não encontrado no PATH."
}
else {
    $gitVersion = Invoke-Capture $gitCommand.Source @("--version")
    Add-Check "git" "PASS" $gitVersion.Text

    $head = Invoke-Capture $gitCommand.Source @(
        "-C",
        $repoRoot,
        "rev-parse",
        "HEAD"
    )
    if (
        ($head.ExitCode -eq 0) -and
        ($head.Text -match '^[0-9a-fA-F]{40}
        $context.sourceCommit = $head.Text.ToLowerInvariant()
        Add-Check "git-head" "PASS" "Commit $($context.sourceCommit)"
    }
    else {
        Add-Check "git-head" "FAIL" "Não foi possível resolver HEAD."
    }

    $status = Invoke-Capture $gitCommand.Source @(
        "-C",
        $repoRoot,
        "status",
        "--porcelain",
        "--untracked-files=all"
    )
    if ($status.ExitCode -ne 0) {
        Add-Check "git-clean" "FAIL" "git status falhou."
    }
    elseif ([string]::IsNullOrWhiteSpace($status.Text)) {
        Add-Check "git-clean" "PASS" "Árvore Git limpa."
    }
    else {
        Add-Check "git-clean" "FAIL" "Árvore Git possui alterações/untracked files."
    }
}

$python = Resolve-Python3
if ($python) {
    $context.python = $python.Version
    Add-Check "python3" "PASS" $python.Version
}
else {
    Add-Check "python3" "FAIL" "Python 3 funcional não encontrado."
}

if ($lock) {
    $java = Resolve-Java $JavaHome ([int]$lock.jdk.major)
    if ($java) {
        $context.javaHome = $java.Home
        Add-Check "java" "PASS" "JDK $($lock.jdk.major) em $($java.Home)"
    }
    else {
        Add-Check "java" "FAIL" "JDK $($lock.jdk.major) não encontrado."
    }
}

$sdkRoot = Resolve-Sdk $AndroidSdkRoot
if ($sdkRoot) {
    $context.androidSdkRoot = $sdkRoot
    Add-Check "android-sdk" "PASS" $sdkRoot

    if ($lock) {
        foreach ($component in $lock.android.requiredComponents) {
            $relative = (
                [string]$component
            ).Replace(
                ";",
                [string][IO.Path]::DirectorySeparatorChar
            )
            $componentPath = Join-Path $sdkRoot $relative
            if (Test-Path $componentPath) {
                Add-Check "sdk:$component" "PASS" "Instalado."
            }
            else {
                if ($AllowMissingSdkComponents) {
                    Add-Check "sdk:$component" "WARN" "Componente ausente; builder está autorizado a instalar."
                }
                else {
                    Add-Check "sdk:$component" "FAIL" "Componente ausente."
                }
            }
        }
    }

    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (Test-Path $adb -PathType Leaf) {
        $context.adb = $adb
        $adbVersion = Invoke-Capture $adb @("version")
        Add-Check "adb" "PASS" (($adbVersion.Text -split '\r?\n')[0])
    }
    else {
        $adb = $null
        Add-Check "adb" "FAIL" "adb.exe ausente em platform-tools."
    }

    $sdkFree = Get-FreeBytesForPath $sdkRoot
    if ($null -ne $sdkFree) {
        $sdkFreeGb = [Math]::Round($sdkFree / 1GB, 2)
        if ($sdkFree -lt 3GB) {
            Add-Check "sdk-disk-free" "FAIL" "Apenas $sdkFreeGb GB livres."
        }
        elseif ($sdkFree -lt 8GB) {
            Add-Check "sdk-disk-free" "WARN" "$sdkFreeGb GB livres; espaço apertado."
        }
        else {
            Add-Check "sdk-disk-free" "PASS" "$sdkFreeGb GB livres."
        }
    }
}
else {
    $adb = $null
    Add-Check "android-sdk" "FAIL" "Android SDK não encontrado."
}

$repoFree = Get-FreeBytesForPath $repoRoot
if ($null -ne $repoFree) {
    $repoFreeGb = [Math]::Round($repoFree / 1GB, 2)
    if ($repoFree -lt 3GB) {
        Add-Check "repo-disk-free" "FAIL" "Apenas $repoFreeGb GB livres."
    }
    elseif ($repoFree -lt 8GB) {
        Add-Check "repo-disk-free" "WARN" "$repoFreeGb GB livres; espaço apertado."
    }
    else {
        Add-Check "repo-disk-free" "PASS" "$repoFreeGb GB livres."
    }
}

if ($lock -and $env:LOCALAPPDATA) {
    $gradleZip = Join-Path $env:LOCALAPPDATA (
        "PocketPC\toolchains\gradle-$($lock.gradle.version)-bin.zip"
    )
    if (Test-Path $gradleZip -PathType Leaf) {
        $actualGradleHash = (
            Get-FileHash $gradleZip -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        $expectedGradleHash = (
            [string]$lock.gradle.distributionSha256
        ).ToLowerInvariant()

        if ($actualGradleHash -eq $expectedGradleHash) {
            Add-Check "gradle-cache" "PASS" "Gradle ZIP cache hash correto."
        }
        else {
            Add-Check "gradle-cache" "FAIL" "Gradle ZIP cache SHA-256 divergiu."
        }
    }
    else {
        Add-Check "gradle-cache" "WARN" "Gradle ZIP ainda não está no cache; builder fará download."
    }
}

$selectedSerial = $null
if ($adb) {
    $devices = Invoke-Capture $adb @("devices", "-l")
    $online = New-Object System.Collections.Generic.List[string]
    $unauthorized = New-Object System.Collections.Generic.List[string]
    $offline = New-Object System.Collections.Generic.List[string]

    foreach ($line in ($devices.Text -split '\r?\n')) {
        if ($line -match '^(?<serial>[^\s]+)\s+(?<state>device|unauthorized|offline)(?:\s|$)') {
            $serial = $Matches.serial
            $state = $Matches.state

            if ($state -eq "device") {
                if ($serial -notlike "emulator-*") {
                    $online.Add($serial)
                }
            }
            elseif ($state -eq "unauthorized") {
                $unauthorized.Add($serial)
            }
            elseif ($state -eq "offline") {
                $offline.Add($serial)
            }
        }
    }

    if ($unauthorized.Count -gt 0) {
        Add-Check "adb-authorization" "FAIL" "Há aparelho ADB unauthorized; confirme a chave RSA no telefone."
    }
    elseif ($offline.Count -gt 0) {
        Add-Check "adb-authorization" "FAIL" "Há aparelho ADB offline."
    }
    else {
        Add-Check "adb-authorization" "PASS" "Nenhum aparelho unauthorized/offline detectado."
    }

    if ($DeviceSerial) {
        if ($online -contains $DeviceSerial) {
            $selectedSerial = $DeviceSerial
        }
        else {
            Add-Check "physical-device" "FAIL" "DeviceSerial solicitado não está online como aparelho físico."
        }
    }
    elseif ($online.Count -eq 1) {
        $selectedSerial = $online[0]
    }
    elseif ($online.Count -gt 1) {
        Add-Check "physical-device" "FAIL" "Mais de um aparelho físico online; informe -DeviceSerial."
    }
    elseif ($RequireDevice) {
        Add-Check "physical-device" "FAIL" "Nenhum aparelho físico ADB autorizado encontrado."
    }
    else {
        Add-Check "physical-device" "WARN" "Nenhum aparelho físico conectado; build-only ainda pode funcionar."
    }

    if ($selectedSerial) {
        $context.deviceSerialSha256 = Sha256Text $selectedSerial

        function Device-Shell {
            param([string[]]$Arguments)
            return Invoke-Capture $adb (
                @("-s", $selectedSerial, "shell") + $Arguments
            )
        }

        $manufacturer = (Device-Shell @(
            "getprop",
            "ro.product.manufacturer"
        )).Text.Trim()
        $model = (Device-Shell @(
            "getprop",
            "ro.product.model"
        )).Text.Trim()
        $apiText = (Device-Shell @(
            "getprop",
            "ro.build.version.sdk"
        )).Text.Trim()
        $abisText = (Device-Shell @(
            "getprop",
            "ro.product.cpu.abilist"
        )).Text.Trim()
        $qemu = (Device-Shell @(
            "getprop",
            "ro.kernel.qemu"
        )).Text.Trim()

        $context.deviceManufacturer = $manufacturer
        $context.deviceModel = $model
        $context.deviceApi = $apiText
        $context.deviceAbis = $abisText

        if ($qemu -eq "1") {
            Add-Check "physical-device" "FAIL" "ro.kernel.qemu=1; aparelho não é físico."
        }
        else {
            Add-Check "physical-device" "PASS" "$manufacturer $model"
        }

        $abis = @(
            $abisText -split "," |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ }
        )
        if ($abis -contains "arm64-v8a") {
            Add-Check "device-abi" "PASS" "arm64-v8a disponível."
        }
        else {
            Add-Check "device-abi" "FAIL" "arm64-v8a ausente."
        }

        $api = 0
        if ([int]::TryParse($apiText, [ref]$api)) {
            if ($api -ge 26) {
                Add-Check "device-api" "PASS" "Android API $api."
            }
            else {
                Add-Check "device-api" "FAIL" "Android API $api abaixo do minSdk 26."
            }
        }
        else {
            Add-Check "device-api" "FAIL" "API Android não pôde ser lida."
        }

        $dataDf = Device-Shell @("df", "-k", "/data")
        $dataLines = @(
            $dataDf.Text -split '\r?\n' |
                Where-Object { $_ -match '^\S+' }
        )
        if ($dataLines.Count -ge 2) {
            $columns = @(
                $dataLines[-1] -split '\s+' |
                    Where-Object { $_ }
            )
            if ($columns.Count -ge 4) {
                $availableKb = 0L
                if ([long]::TryParse($columns[3], [ref]$availableKb)) {
                    $availableBytes = $availableKb * 1KB
                    $availableGb = [Math]::Round(
                        $availableBytes / 1GB,
                        2
                    )
                    if ($availableBytes -lt 256MB) {
                        Add-Check "device-data-free" "FAIL" "Apenas $availableGb GB livres em /data."
                    }
                    elseif ($availableBytes -lt 1GB) {
                        Add-Check "device-data-free" "WARN" "$availableGb GB livres em /data."
                    }
                    else {
                        Add-Check "device-data-free" "PASS" "$availableGb GB livres em /data."
                    }
                }
            }
        }
    }
}

if ($CheckNetwork) {
    foreach ($hostName in @(
        "services.gradle.org",
        "dl.google.com"
    )) {
        $ok = Test-NetConnection $hostName -Port 443 -InformationLevel Quiet
        if ($ok) {
            Add-Check "network:$hostName" "PASS" "TCP/443 alcançável."
        }
        else {
            Add-Check "network:$hostName" "WARN" "TCP/443 não respondeu ao preflight."
        }
    }
}

$failCount = @($checks | Where-Object { $_.status -eq "FAIL" }).Count
$warnCount = @($checks | Where-Object { $_.status -eq "WARN" }).Count
$passCount = @($checks | Where-Object { $_.status -eq "PASS" }).Count

$classification = if ($failCount -gt 0) {
    "PREFLIGHT_FAIL"
}
elseif ($warnCount -gt 0) {
    "PREFLIGHT_PASS_WITH_WARNINGS"
}
else {
    "PREFLIGHT_PASS"
}

if (-not $OutputPath) {
    $preflightDir = Join-Path $repoRoot "local-build\preflight"
    New-Item -ItemType Directory -Force -Path $preflightDir | Out-Null
    $OutputPath = Join-Path $preflightDir "preflight-record.json"
}
else {
    $parent = Split-Path $OutputPath -Parent
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
}

$record = [ordered]@{
    schemaVersion = 1
    classification = $classification
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    passCount = $passCount
    warnCount = $warnCount
    failCount = $failCount
    context = $context
    checks = @($checks)
}

$record |
    ConvertTo-Json -Depth 10 |
    Set-Content $OutputPath -Encoding UTF8

$recordSha = (
    Get-FileHash $OutputPath -Algorithm SHA256
).Hash.ToLowerInvariant()

"$recordSha  $([IO.Path]::GetFileName($OutputPath))" |
    Set-Content "$OutputPath.sha256" -Encoding ASCII

Write-Host ""
Write-Host "PocketPC Windows Preflight Doctor" -ForegroundColor Cyan
Write-Host "Classification : $classification"
Write-Host "PASS/WARN/FAIL : $passCount / $warnCount / $failCount"
Write-Host "Record         : $OutputPath"

foreach ($check in $checks) {
    $color = if ($check.status -eq "PASS") {
        "Green"
    }
    elseif ($check.status -eq "WARN") {
        "Yellow"
    }
    else {
        "Red"
    }
    Write-Host "[$($check.status)] $($check.id): $($check.detail)" -ForegroundColor $color
}

if ($failCount -gt 0) {
    throw "PocketPC preflight encontrou $failCount falha(s)."
}
)
    ) {
        $context.sourceCommit = $head.Text.ToLowerInvariant()
        Add-Check "git-head" "PASS" "Commit $($context.sourceCommit)"
    }
    else {
        Add-Check "git-head" "FAIL" "Não foi possível resolver HEAD."
    }

    $status = Invoke-Capture $gitCommand.Source @(
        "-C",
        $repoRoot,
        "status",
        "--porcelain",
        "--untracked-files=all"
    )
    if ($status.ExitCode -ne 0) {
        Add-Check "git-clean" "FAIL" "git status falhou."
    }
    elseif ([string]::IsNullOrWhiteSpace($status.Text)) {
        Add-Check "git-clean" "PASS" "Árvore Git limpa."
    }
    else {
        Add-Check "git-clean" "FAIL" "Árvore Git possui alterações/untracked files."
    }
}

$python = Resolve-Python3
if ($python) {
    $context.python = $python.Version
    Add-Check "python3" "PASS" $python.Version
}
else {
    Add-Check "python3" "FAIL" "Python 3 funcional não encontrado."
}

if ($lock) {
    $java = Resolve-Java $JavaHome ([int]$lock.jdk.major)
    if ($java) {
        $context.javaHome = $java.Home
        Add-Check "java" "PASS" "JDK $($lock.jdk.major) em $($java.Home)"
    }
    else {
        Add-Check "java" "FAIL" "JDK $($lock.jdk.major) não encontrado."
    }
}

$sdkRoot = Resolve-Sdk $AndroidSdkRoot
if ($sdkRoot) {
    $context.androidSdkRoot = $sdkRoot
    Add-Check "android-sdk" "PASS" $sdkRoot

    if ($lock) {
        foreach ($component in $lock.android.requiredComponents) {
            $relative = (
                [string]$component
            ).Replace(
                ";",
                [string][IO.Path]::DirectorySeparatorChar
            )
            $componentPath = Join-Path $sdkRoot $relative
            if (Test-Path $componentPath) {
                Add-Check "sdk:$component" "PASS" "Instalado."
            }
            else {
                if ($AllowMissingSdkComponents) {
                    Add-Check "sdk:$component" "WARN" "Componente ausente; builder está autorizado a instalar."
                }
                else {
                    Add-Check "sdk:$component" "FAIL" "Componente ausente."
                }
            }
        }
    }

    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (Test-Path $adb -PathType Leaf) {
        $context.adb = $adb
        $adbVersion = Invoke-Capture $adb @("version")
        Add-Check "adb" "PASS" (($adbVersion.Text -split '\r?\n')[0])
    }
    else {
        $adb = $null
        Add-Check "adb" "FAIL" "adb.exe ausente em platform-tools."
    }

    $sdkFree = Get-FreeBytesForPath $sdkRoot
    if ($null -ne $sdkFree) {
        $sdkFreeGb = [Math]::Round($sdkFree / 1GB, 2)
        if ($sdkFree -lt 3GB) {
            Add-Check "sdk-disk-free" "FAIL" "Apenas $sdkFreeGb GB livres."
        }
        elseif ($sdkFree -lt 8GB) {
            Add-Check "sdk-disk-free" "WARN" "$sdkFreeGb GB livres; espaço apertado."
        }
        else {
            Add-Check "sdk-disk-free" "PASS" "$sdkFreeGb GB livres."
        }
    }
}
else {
    $adb = $null
    Add-Check "android-sdk" "FAIL" "Android SDK não encontrado."
}

$repoFree = Get-FreeBytesForPath $repoRoot
if ($null -ne $repoFree) {
    $repoFreeGb = [Math]::Round($repoFree / 1GB, 2)
    if ($repoFree -lt 3GB) {
        Add-Check "repo-disk-free" "FAIL" "Apenas $repoFreeGb GB livres."
    }
    elseif ($repoFree -lt 8GB) {
        Add-Check "repo-disk-free" "WARN" "$repoFreeGb GB livres; espaço apertado."
    }
    else {
        Add-Check "repo-disk-free" "PASS" "$repoFreeGb GB livres."
    }
}

if ($lock -and $env:LOCALAPPDATA) {
    $gradleZip = Join-Path $env:LOCALAPPDATA (
        "PocketPC\toolchains\gradle-$($lock.gradle.version)-bin.zip"
    )
    if (Test-Path $gradleZip -PathType Leaf) {
        $actualGradleHash = (
            Get-FileHash $gradleZip -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        $expectedGradleHash = (
            [string]$lock.gradle.distributionSha256
        ).ToLowerInvariant()

        if ($actualGradleHash -eq $expectedGradleHash) {
            Add-Check "gradle-cache" "PASS" "Gradle ZIP cache hash correto."
        }
        else {
            Add-Check "gradle-cache" "FAIL" "Gradle ZIP cache SHA-256 divergiu."
        }
    }
    else {
        Add-Check "gradle-cache" "WARN" "Gradle ZIP ainda não está no cache; builder fará download."
    }
}

$selectedSerial = $null
if ($adb) {
    $devices = Invoke-Capture $adb @("devices", "-l")
    $online = New-Object System.Collections.Generic.List[string]
    $unauthorized = New-Object System.Collections.Generic.List[string]
    $offline = New-Object System.Collections.Generic.List[string]

    foreach ($line in ($devices.Text -split '\r?\n')) {
        if ($line -match '^(?<serial>[^\s]+)\s+(?<state>device|unauthorized|offline)(?:\s|$)') {
            $serial = $Matches.serial
            $state = $Matches.state

            if ($state -eq "device") {
                if ($serial -notlike "emulator-*") {
                    $online.Add($serial)
                }
            }
            elseif ($state -eq "unauthorized") {
                $unauthorized.Add($serial)
            }
            elseif ($state -eq "offline") {
                $offline.Add($serial)
            }
        }
    }

    if ($unauthorized.Count -gt 0) {
        Add-Check "adb-authorization" "FAIL" "Há aparelho ADB unauthorized; confirme a chave RSA no telefone."
    }
    elseif ($offline.Count -gt 0) {
        Add-Check "adb-authorization" "FAIL" "Há aparelho ADB offline."
    }
    else {
        Add-Check "adb-authorization" "PASS" "Nenhum aparelho unauthorized/offline detectado."
    }

    if ($DeviceSerial) {
        if ($online -contains $DeviceSerial) {
            $selectedSerial = $DeviceSerial
        }
        else {
            Add-Check "physical-device" "FAIL" "DeviceSerial solicitado não está online como aparelho físico."
        }
    }
    elseif ($online.Count -eq 1) {
        $selectedSerial = $online[0]
    }
    elseif ($online.Count -gt 1) {
        Add-Check "physical-device" "FAIL" "Mais de um aparelho físico online; informe -DeviceSerial."
    }
    elseif ($RequireDevice) {
        Add-Check "physical-device" "FAIL" "Nenhum aparelho físico ADB autorizado encontrado."
    }
    else {
        Add-Check "physical-device" "WARN" "Nenhum aparelho físico conectado; build-only ainda pode funcionar."
    }

    if ($selectedSerial) {
        $context.deviceSerialSha256 = Sha256Text $selectedSerial

        function Device-Shell {
            param([string[]]$Arguments)
            return Invoke-Capture $adb (
                @("-s", $selectedSerial, "shell") + $Arguments
            )
        }

        $manufacturer = (Device-Shell @(
            "getprop",
            "ro.product.manufacturer"
        )).Text.Trim()
        $model = (Device-Shell @(
            "getprop",
            "ro.product.model"
        )).Text.Trim()
        $apiText = (Device-Shell @(
            "getprop",
            "ro.build.version.sdk"
        )).Text.Trim()
        $abisText = (Device-Shell @(
            "getprop",
            "ro.product.cpu.abilist"
        )).Text.Trim()
        $qemu = (Device-Shell @(
            "getprop",
            "ro.kernel.qemu"
        )).Text.Trim()

        $context.deviceManufacturer = $manufacturer
        $context.deviceModel = $model
        $context.deviceApi = $apiText
        $context.deviceAbis = $abisText

        if ($qemu -eq "1") {
            Add-Check "physical-device" "FAIL" "ro.kernel.qemu=1; aparelho não é físico."
        }
        else {
            Add-Check "physical-device" "PASS" "$manufacturer $model"
        }

        $abis = @(
            $abisText -split "," |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ }
        )
        if ($abis -contains "arm64-v8a") {
            Add-Check "device-abi" "PASS" "arm64-v8a disponível."
        }
        else {
            Add-Check "device-abi" "FAIL" "arm64-v8a ausente."
        }

        $api = 0
        if ([int]::TryParse($apiText, [ref]$api)) {
            if ($api -ge 26) {
                Add-Check "device-api" "PASS" "Android API $api."
            }
            else {
                Add-Check "device-api" "FAIL" "Android API $api abaixo do minSdk 26."
            }
        }
        else {
            Add-Check "device-api" "FAIL" "API Android não pôde ser lida."
        }

        $dataDf = Device-Shell @("df", "-k", "/data")
        $dataLines = @(
            $dataDf.Text -split '\r?\n' |
                Where-Object { $_ -match '^\S+' }
        )
        if ($dataLines.Count -ge 2) {
            $columns = @(
                $dataLines[-1] -split '\s+' |
                    Where-Object { $_ }
            )
            if ($columns.Count -ge 4) {
                $availableKb = 0L
                if ([long]::TryParse($columns[3], [ref]$availableKb)) {
                    $availableBytes = $availableKb * 1KB
                    $availableGb = [Math]::Round(
                        $availableBytes / 1GB,
                        2
                    )
                    if ($availableBytes -lt 256MB) {
                        Add-Check "device-data-free" "FAIL" "Apenas $availableGb GB livres em /data."
                    }
                    elseif ($availableBytes -lt 1GB) {
                        Add-Check "device-data-free" "WARN" "$availableGb GB livres em /data."
                    }
                    else {
                        Add-Check "device-data-free" "PASS" "$availableGb GB livres em /data."
                    }
                }
            }
        }
    }
}

if ($CheckNetwork) {
    foreach ($hostName in @(
        "services.gradle.org",
        "dl.google.com"
    )) {
        $ok = Test-NetConnection $hostName -Port 443 -InformationLevel Quiet
        if ($ok) {
            Add-Check "network:$hostName" "PASS" "TCP/443 alcançável."
        }
        else {
            Add-Check "network:$hostName" "WARN" "TCP/443 não respondeu ao preflight."
        }
    }
}

$failCount = @($checks | Where-Object { $_.status -eq "FAIL" }).Count
$warnCount = @($checks | Where-Object { $_.status -eq "WARN" }).Count
$passCount = @($checks | Where-Object { $_.status -eq "PASS" }).Count

$classification = if ($failCount -gt 0) {
    "PREFLIGHT_FAIL"
}
elseif ($warnCount -gt 0) {
    "PREFLIGHT_PASS_WITH_WARNINGS"
}
else {
    "PREFLIGHT_PASS"
}

if (-not $OutputPath) {
    $preflightDir = Join-Path $repoRoot "local-build\preflight"
    New-Item -ItemType Directory -Force -Path $preflightDir | Out-Null
    $OutputPath = Join-Path $preflightDir "preflight-record.json"
}
else {
    $parent = Split-Path $OutputPath -Parent
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
}

$record = [ordered]@{
    schemaVersion = 1
    classification = $classification
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    passCount = $passCount
    warnCount = $warnCount
    failCount = $failCount
    context = $context
    checks = @($checks)
}

$record |
    ConvertTo-Json -Depth 10 |
    Set-Content $OutputPath -Encoding UTF8

$recordSha = (
    Get-FileHash $OutputPath -Algorithm SHA256
).Hash.ToLowerInvariant()

"$recordSha  $([IO.Path]::GetFileName($OutputPath))" |
    Set-Content "$OutputPath.sha256" -Encoding ASCII

Write-Host ""
Write-Host "PocketPC Windows Preflight Doctor" -ForegroundColor Cyan
Write-Host "Classification : $classification"
Write-Host "PASS/WARN/FAIL : $passCount / $warnCount / $failCount"
Write-Host "Record         : $OutputPath"

foreach ($check in $checks) {
    $color = if ($check.status -eq "PASS") {
        "Green"
    }
    elseif ($check.status -eq "WARN") {
        "Yellow"
    }
    else {
        "Red"
    }
    Write-Host "[$($check.status)] $($check.id): $($check.detail)" -ForegroundColor $color
}

if ($failCount -gt 0) {
    throw "PocketPC preflight encontrou $failCount falha(s)."
}
