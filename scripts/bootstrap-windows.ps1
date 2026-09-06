[CmdletBinding()]
param(
    [string]$RepoRoot,
    [switch]$SkipDoctor
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    throw $Message
}

function Ensure-Directory([string]$Path) {
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Add-UserPath([string]$Entry) {
    $current = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($null -eq $current) {
        $current = ""
    }

    $parts = @(
        $current -split ";" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )

    $exists = $false
    foreach ($part in $parts) {
        if ($part.TrimEnd("\") -ieq $Entry.TrimEnd("\")) {
            $exists = $true
            break
        }
    }

    if (-not $exists) {
        $newPath = if ([string]::IsNullOrWhiteSpace($current)) {
            $Entry
        }
        else {
            "$current;$Entry"
        }
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    }

    $processParts = @($env:Path -split ";")
    if (-not ($processParts -contains $Entry)) {
        $env:Path = "$Entry;$env:Path"
    }
}

function Test-Jdk17([string]$JdkHome) {
    if ([string]::IsNullOrWhiteSpace($JdkHome)) {
        return $false
    }

    $java = Join-Path $JdkHome "bin\java.exe"
    $javac = Join-Path $JdkHome "bin\javac.exe"
    if (-not (Test-Path $java -PathType Leaf)) {
        return $false
    }
    if (-not (Test-Path $javac -PathType Leaf)) {
        return $false
    }

    $version = & $java -version 2>&1 | Out-String
    return ($version -match 'version\s+"17[.]')
}

function Find-Repo {
    param([string]$Explicit)

    $candidates = @(
        $Explicit,
        "D:\Projetos\PocketPC",
        (Join-Path $env:USERPROFILE "Desktop\PocketPC"),
        (Join-Path $env:USERPROFILE "Documents\PocketPC"),
        (Join-Path $env:USERPROFILE "Downloads\PocketPC")
    )

    foreach ($candidate in $candidates) {
        if (
            $candidate -and
            (Test-Path (Join-Path $candidate ".git") -PathType Container) -and
            (Test-Path (Join-Path $candidate "toolchains\android-build-lock.json") -PathType Leaf)
        ) {
            return (Resolve-Path $candidate).Path
        }
    }

    return $null
}

$workRoot = Join-Path $env:LOCALAPPDATA "PocketPC"
$downloadRoot = Join-Path $workRoot "downloads"
$toolchainRoot = Join-Path $workRoot "toolchains"
$setupRoot = Join-Path $workRoot "setup"
Ensure-Directory $downloadRoot
Ensure-Directory $toolchainRoot
Ensure-Directory $setupRoot

$timestamp = [DateTime]::Now.ToString("yyyyMMdd-HHmmss")
$logPath = Join-Path $setupRoot "setup-$timestamp.log"
$transcriptStarted = $false

try {
    try {
        Start-Transcript -Path $logPath -Force | Out-Null
        $transcriptStarted = $true
    }
    catch {
        Write-Warning "Nao foi possivel iniciar transcript: $($_.Exception.Message)"
    }

    Write-Host "PocketPC Windows Setup" -ForegroundColor Green
    Write-Host "Sem Android Studio. JDK + Android CLI oficiais." -ForegroundColor Green
    Write-Host "Log: $logPath"

    Step "Localizando repositorio PocketPC"
    $repo = Find-Repo $RepoRoot
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if (-not $gitCommand) {
        $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    }
    if (-not $gitCommand) {
        Fail "Git nao encontrado. O PocketPC precisa do Git para atualizar/clonar o repositorio."
    }

    if (-not $repo) {
        $target = if (Test-Path "D:\" -PathType Container) {
            "D:\Projetos\PocketPC"
        }
        else {
            Join-Path $env:USERPROFILE "PocketPC"
        }

        Ensure-Directory (Split-Path $target -Parent)
        Step "Clonando PocketPC em $target"
        & $gitCommand.Source clone "https://github.com/Rogeriomatador/PocketPC.git" $target
        if ($LASTEXITCODE -ne 0) {
            Fail "git clone falhou."
        }
        $repo = (Resolve-Path $target).Path
    }
    else {
        Write-Host "Repositorio: $repo"
        Step "Atualizando repositorio"
        & $gitCommand.Source -C $repo pull --ff-only
        if ($LASTEXITCODE -ne 0) {
            Fail "git pull --ff-only falhou. Verifique se a arvore Git esta limpa."
        }
    }

    $lockPath = Join-Path $repo "toolchains\android-build-lock.json"
    $lock = Get-Content $lockPath -Raw | ConvertFrom-Json
    if (($lock.schemaVersion -ne 1) -or ($lock.status -ne "PINNED")) {
        Fail "android-build-lock.json invalido ou nao PINNED."
    }

    Step "Preparando Eclipse Temurin JDK 17"
    $jdkRoot = Join-Path $toolchainRoot "temurin17"

    if (-not (Test-Jdk17 $jdkRoot)) {
        $api = "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
        Write-Host "Consultando Adoptium API oficial..."
        $assets = @(Invoke-RestMethod -Uri $api -Method Get)

        $asset = $assets |
            Where-Object {
                $_.binary -and
                $_.binary.package -and
                ([string]$_.binary.package.name) -match '[.]zip$'
            } |
            Select-Object -First 1

        if (-not $asset) {
            Fail "Adoptium API nao retornou JDK 17 Windows x64 ZIP."
        }

        $jdkUrl = [string]$asset.binary.package.link
        $jdkExpectedSha = ([string]$asset.binary.package.checksum).ToLowerInvariant()
        $jdkName = [string]$asset.binary.package.name

        if ($jdkExpectedSha -notmatch '^[0-9a-f]{64}$') {
            Fail "Checksum SHA-256 do JDK retornado pela Adoptium e invalido."
        }

        $jdkZip = Join-Path $downloadRoot $jdkName
        $needJdkDownload = $true
        if (Test-Path $jdkZip -PathType Leaf) {
            $cachedSha = (Get-FileHash $jdkZip -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($cachedSha -eq $jdkExpectedSha) {
                $needJdkDownload = $false
                Write-Host "JDK ZIP ja esta no cache com SHA-256 valido."
            }
            else {
                Remove-Item $jdkZip -Force
            }
        }

        if ($needJdkDownload) {
            Write-Host "Baixando JDK 17 oficial..."
            Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing
        }

        $jdkActualSha = (Get-FileHash $jdkZip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($jdkActualSha -ne $jdkExpectedSha) {
            Fail "JDK SHA-256 divergiu do checksum oficial."
        }

        $jdkStage = Join-Path $toolchainRoot "temurin17-stage"
        if (Test-Path $jdkStage) {
            Remove-Item $jdkStage -Recurse -Force
        }
        Ensure-Directory $jdkStage
        Expand-Archive -Path $jdkZip -DestinationPath $jdkStage -Force

        $jdkCandidate = Get-ChildItem $jdkStage -Directory -Recurse |
            Where-Object {
                (Test-Path (Join-Path $_.FullName "bin\java.exe") -PathType Leaf) -and
                (Test-Path (Join-Path $_.FullName "bin\javac.exe") -PathType Leaf)
            } |
            Select-Object -First 1

        if (-not $jdkCandidate) {
            Fail "JDK extraido, mas bin\java.exe/bin\javac.exe nao foram encontrados."
        }

        if (Test-Path $jdkRoot) {
            Remove-Item $jdkRoot -Recurse -Force
        }
        Move-Item $jdkCandidate.FullName $jdkRoot
        Remove-Item $jdkStage -Recurse -Force -ErrorAction SilentlyContinue
    }

    if (-not (Test-Jdk17 $jdkRoot)) {
        Fail "JDK 17 nao passou na verificacao pos-instalacao."
    }

    $env:JAVA_HOME = $jdkRoot
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkRoot, "User")
    Add-UserPath (Join-Path $jdkRoot "bin")
    Write-Host "JAVA_HOME: $jdkRoot"

    Step "Preparando Android SDK Command-line Tools"
    $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    Ensure-Directory $sdkRoot
    Ensure-Directory (Join-Path $sdkRoot "cmdline-tools")

    $cmdZipName = "commandlinetools-win-15859902_latest.zip"
    $cmdUrl = "https://dl.google.com/android/repository/$cmdZipName"
    $cmdExpectedSha = "90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a"
    $cmdZip = Join-Path $downloadRoot $cmdZipName
    $sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"

    if (-not (Test-Path $sdkManager -PathType Leaf)) {
        $needCmdDownload = $true
        if (Test-Path $cmdZip -PathType Leaf) {
            $cachedCmdSha = (Get-FileHash $cmdZip -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($cachedCmdSha -eq $cmdExpectedSha) {
                $needCmdDownload = $false
                Write-Host "Android CLI ZIP ja esta no cache com SHA-256 valido."
            }
            else {
                Remove-Item $cmdZip -Force
            }
        }

        if ($needCmdDownload) {
            Write-Host "Baixando Android Command-line Tools oficiais..."
            Invoke-WebRequest -Uri $cmdUrl -OutFile $cmdZip -UseBasicParsing
        }

        $cmdActualSha = (Get-FileHash $cmdZip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($cmdActualSha -ne $cmdExpectedSha) {
            Fail "Android Command-line Tools SHA-256 divergiu do checksum oficial."
        }

        $cmdStage = Join-Path $toolchainRoot "android-cli-stage"
        if (Test-Path $cmdStage) {
            Remove-Item $cmdStage -Recurse -Force
        }
        Ensure-Directory $cmdStage
        Expand-Archive -Path $cmdZip -DestinationPath $cmdStage -Force

        $sourceTools = Join-Path $cmdStage "cmdline-tools"
        if (-not (Test-Path (Join-Path $sourceTools "bin\sdkmanager.bat") -PathType Leaf)) {
            Fail "Android CLI ZIP extraido em formato inesperado."
        }

        $latest = Join-Path $sdkRoot "cmdline-tools\latest"
        if (Test-Path $latest) {
            Remove-Item $latest -Recurse -Force
        }
        Move-Item $sourceTools $latest
        Remove-Item $cmdStage -Recurse -Force -ErrorAction SilentlyContinue
    }

    if (-not (Test-Path $sdkManager -PathType Leaf)) {
        Fail "sdkmanager.bat nao encontrado apos instalar Android CLI."
    }

    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_HOME = $sdkRoot
    [Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $sdkRoot, "User")
    [Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkRoot, "User")
    Add-UserPath (Join-Path $sdkRoot "platform-tools")
    Add-UserPath (Join-Path $sdkRoot "cmdline-tools\latest\bin")

    Step "Licencas Android SDK"
    Write-Host "Para instalar os componentes Android, voce precisa aceitar as licencas do SDK."
    $answer = Read-Host "Digite S para aceitar e continuar"
    if ($answer -notmatch '^(?i:s|sim|y|yes)$') {
        Fail "Licencas Android nao aceitas; instalacao interrompida sem instalar componentes."
    }

    1..100 |
        ForEach-Object { "y" } |
        & $sdkManager "--sdk_root=$sdkRoot" "--licenses"

    if ($LASTEXITCODE -ne 0) {
        Fail "sdkmanager --licenses falhou com codigo $LASTEXITCODE."
    }

    Step "Instalando componentes Android pinados pelo PocketPC"
    $components = @($lock.android.requiredComponents)
    foreach ($component in $components) {
        Write-Host "  - $component"
    }

    & $sdkManager "--sdk_root=$sdkRoot" $components
    if ($LASTEXITCODE -ne 0) {
        Fail "sdkmanager falhou ao instalar os componentes requeridos."
    }

    foreach ($component in $components) {
        $relative = ([string]$component).Replace(
            ";",
            [string][IO.Path]::DirectorySeparatorChar
        )
        $componentPath = Join-Path $sdkRoot $relative
        if (-not (Test-Path $componentPath)) {
            Fail "Componente Android ausente apos instalacao: $component"
        }
    }

    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path $adb -PathType Leaf)) {
        Fail "adb.exe nao encontrado apos instalar platform-tools."
    }

    Step "Resumo"
    Write-Host "JDK 17      : OK"
    Write-Host "JAVA_HOME   : $jdkRoot"
    Write-Host "Android SDK : OK"
    Write-Host "SDK root    : $sdkRoot"
    Write-Host "ADB         : $adb"
    Write-Host "Repo        : $repo"
    Write-Host "Log         : $logPath"

    if (-not $SkipDoctor) {
        Step "Executando PocketPC Doctor com celular"
        $doctor = Join-Path $repo "scripts\doctor-windows.ps1"
        $doctorArgs = @{
            JavaHome = $jdkRoot
            AndroidSdkRoot = $sdkRoot
            RequireDevice = $true
            CheckNetwork = $true
        }
        & $doctor @doctorArgs
    }

    Write-Host ""
    Write-Host "POCKETPC_WINDOWS_SETUP_OK" -ForegroundColor Green
}
catch {
    Write-Host ""
    Write-Host "POCKETPC_WINDOWS_SETUP_FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host "Log: $logPath" -ForegroundColor Yellow
    exit 1
}
finally {
    if ($transcriptStarted) {
        try {
            Stop-Transcript | Out-Null
        }
        catch {
        }
    }
}
