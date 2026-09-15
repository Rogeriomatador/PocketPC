param(
    [string]$Repository = "Rogeriomatador/PocketPC",
    [string]$OtaRepository = "Rogeriomatador/PocketPC-Updates",
    [string]$DevelopmentBranch = "improve/alpha22-desktop-continuity",
    [string]$RepoRoot = "",
    [string]$AndroidSdkRoot = "",
    [string]$JavaHome = "",
    [string]$KeystorePath = "",
    [string]$StorePassword = "android",
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeyPassword = "android",
    [switch]$InstallNow,
    [switch]$SkipDebugValidation
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    throw ("POCKETPC_HOME_OTA_BOOTSTRAP_FAILED: " + $Message)
}

function Resolve-CommandPath([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    return $null
}

function Invoke-Native {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @Arguments
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        Fail ("Command failed (" + $code + "): " + $FilePath + " " + ($Arguments -join " "))
    }
}

function Invoke-Capture {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $text = (& $FilePath @Arguments | Out-String).Trim()
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        Fail ("Command failed (" + $code + "): " + $FilePath + " " + ($Arguments -join " "))
    }
    return $text
}

function Try-Native {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @Arguments | Out-Host
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Resolve-Python {
    $python = Resolve-CommandPath @("python.exe", "python")
    if ($python) {
        return [pscustomobject]@{ File = $python; Prefix = @() }
    }
    $py = Resolve-CommandPath @("py.exe", "py")
    if ($py) {
        return [pscustomobject]@{ File = $py; Prefix = @("-3") }
    }
    Fail "Python 3 nao foi encontrado."
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Split-Path -Parent $PSScriptRoot
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$git = Resolve-CommandPath @("git.exe", "git")
$gh = Resolve-CommandPath @("gh.exe", "gh")
if (-not $git) { Fail "git nao foi encontrado." }
if (-not $gh) { Fail "GitHub CLI (gh) nao foi encontrado." }
$python = Resolve-Python

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $AndroidSdkRoot = $env:ANDROID_SDK_ROOT
    } elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot) -or -not (Test-Path -LiteralPath $AndroidSdkRoot)) {
    Fail "Android SDK nao foi encontrado."
}
$AndroidSdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JavaHome = $env:JAVA_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidate = Join-Path $env:LOCALAPPDATA "PocketPC\toolchains\temurin17"
        if (Test-Path -LiteralPath $candidate) { $JavaHome = $candidate }
    }
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) {
    Fail "JDK 17 do PocketPC nao foi encontrado."
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $env:USERPROFILE ".android\debug.keystore"
}
if (-not (Test-Path -LiteralPath $KeystorePath)) {
    Fail ("Keystore fisica do bootstrap nao encontrada: " + $KeystorePath)
}
$KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path

Write-Host "============================================================"
Write-Host "PocketPC - Home Test OTA Bootstrap"
Write-Host "============================================================"
Write-Host ("Source repo : " + $Repository)
Write-Host ("OTA repo    : " + $OtaRepository)
Write-Host ("Branch      : " + $DevelopmentBranch)

$status = Invoke-Capture $git @("-C", $RepoRoot, "status", "--porcelain")
if (-not [string]::IsNullOrWhiteSpace($status)) {
    Fail "Repositorio local possui alteracoes nao commitadas."
}

Invoke-Native $git @("-C", $RepoRoot, "fetch", "origin", $DevelopmentBranch)
$currentBranch = Invoke-Capture $git @("-C", $RepoRoot, "branch", "--show-current")
if ($currentBranch -ne $DevelopmentBranch) {
    $branchExists = Try-Native $git @("-C", $RepoRoot, "show-ref", "--verify", "--quiet", ("refs/heads/" + $DevelopmentBranch))
    if ($branchExists -eq 0) {
        Invoke-Native $git @("-C", $RepoRoot, "checkout", $DevelopmentBranch)
    } else {
        Invoke-Native $git @("-C", $RepoRoot, "checkout", "-b", $DevelopmentBranch, ("origin/" + $DevelopmentBranch))
    }
}
Invoke-Native $git @("-C", $RepoRoot, "pull", "--ff-only", "origin", $DevelopmentBranch)

$head = (Invoke-Capture $git @("-C", $RepoRoot, "rev-parse", "HEAD")).ToLowerInvariant()
if ($head -notmatch "^[0-9a-f]{40}$") { Fail "Git HEAD invalido." }

$authExit = Try-Native $gh @("auth", "status")
if ($authExit -ne 0) {
    Fail "GitHub CLI nao esta autenticado. Execute gh auth login uma vez."
}

$bootstrap = Join-Path $RepoRoot "scripts\bootstrap-update-signing-windows.ps1"
& $bootstrap `
    -Repository $Repository `
    -RepoRoot $RepoRoot `
    -KeystorePath $KeystorePath `
    -StorePassword $StorePassword `
    -KeyAlias $KeyAlias `
    -KeyPassword $KeyPassword

$otaViewExit = Try-Native $gh @("repo", "view", $OtaRepository, "--json", "visibility,nameWithOwner")
if ($otaViewExit -ne 0) {
    Write-Host "Criando repositorio publico somente para artefatos OTA..."
    Invoke-Native $gh @(
        "repo", "create", $OtaRepository,
        "--public",
        "--description", "Signed PocketPC OTA artifacts only - no private source code",
        "--add-readme"
    )
}

$otaVisibility = Invoke-Capture $gh @("repo", "view", $OtaRepository, "--json", "visibility", "--jq", ".visibility")
if ($otaVisibility.Trim().ToUpperInvariant() -ne "PUBLIC") {
    Fail "PocketPC-Updates existe, mas nao esta PUBLIC."
}
Write-Host "OTA artifact repository: PUBLIC" -ForegroundColor Green

$ghToken = Invoke-Capture $gh @("auth", "token")
if ([string]::IsNullOrWhiteSpace($ghToken)) {
    Fail "Nao foi possivel obter o token autenticado do GitHub CLI."
}
try {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $ghToken | & $gh secret set POCKETPC_OTA_PUBLISH_TOKEN --repo $Repository | Out-Host
        $secretExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($secretExit -ne 0) { Fail "Falha ao configurar POCKETPC_OTA_PUBLISH_TOKEN." }
}
finally {
    Remove-Variable ghToken -ErrorAction SilentlyContinue
}
Write-Host "OTA publish token: CONFIGURED AS ACTIONS SECRET" -ForegroundColor Green

$commitCountText = Invoke-Capture $git @("-C", $RepoRoot, "rev-list", "--count", "HEAD")
$commitCount = [int]$commitCountText
$versionCode = 220000 + $commitCount
if ($versionCode -ge 230000) {
    Fail "Faixa de versionCode da Alpha 22 esgotada."
}
$versionName = "0.1.0-alpha22.home." + $commitCount
$shortSha = $head.Substring(0, 12)
$tag = "home-alpha22-" + $commitCount + "-" + $shortSha

Write-Host ("Source      : " + $head)
Write-Host ("Version     : " + $versionName)
Write-Host ("VersionCode : " + $versionCode)

if (-not $SkipDebugValidation) {
    $buildScript = Join-Path $RepoRoot "scripts\build-local-windows.ps1"
    & $buildScript -AndroidSdkRoot $AndroidSdkRoot -JavaHome $JavaHome -RequirePythonPolicyChecks
}

$lockPath = Join-Path $RepoRoot "toolchains\android-build-lock.json"
$pinPath = Join-Path $RepoRoot "updates\bootstrap-signer.json"
$lock = Get-Content -LiteralPath $lockPath -Raw -Encoding UTF8 | ConvertFrom-Json
$pin = Get-Content -LiteralPath $pinPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { Fail "LOCALAPPDATA nao esta definido." }
$gradleBat = Join-Path $env:LOCALAPPDATA ("PocketPC\toolchains\gradle-" + [string]$lock.gradle.version + "\bin\gradle.bat")
if (-not (Test-Path -LiteralPath $gradleBat)) {
    Fail ("Gradle pinado nao foi materializado: " + $gradleBat)
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_SDK_ROOT = $AndroidSdkRoot
$env:ANDROID_HOME = $AndroidSdkRoot
$env:POCKETPC_SOURCE_REVISION = $head
$env:POCKETPC_VERSION_CODE = [string]$versionCode
$env:POCKETPC_VERSION_NAME = $versionName
$env:POCKETPC_SIGNING_STORE_FILE = $KeystorePath
$env:POCKETPC_SIGNING_STORE_PASSWORD = $StorePassword
$env:POCKETPC_SIGNING_KEY_ALIAS = $KeyAlias
$env:POCKETPC_SIGNING_KEY_PASSWORD = $KeyPassword

Invoke-Native $gradleBat @("--no-daemon", ":app:testDebugUnitTest", ":app:assembleRelease")

$releaseDir = Join-Path $RepoRoot "app\build\outputs\apk\release"
$rawApk = Join-Path $releaseDir "app-release.apk"
if (-not (Test-Path -LiteralPath $rawApk)) { Fail "Release APK nao foi produzido." }
$namedApk = Join-Path $releaseDir ("PocketPC-" + $versionName + ".apk")
Copy-Item -LiteralPath $rawApk -Destination $namedApk -Force

$apksigner = Join-Path $AndroidSdkRoot ("build-tools\" + [string]$lock.android.buildTools + "\apksigner.bat")
if (-not (Test-Path -LiteralPath $apksigner)) { Fail "apksigner.bat nao foi encontrado." }
$signingText = Invoke-Capture $apksigner @("verify", "--print-certs", $namedApk)
$signerMatch = [regex]::Match($signingText, "Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")
if (-not $signerMatch.Success) { Fail "Nao foi possivel obter SHA-256 do signer." }
$signerSha = $signerMatch.Groups[1].Value.ToLowerInvariant()
$allowed = @($pin.allowedSigningCertificateSha256 | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() })
if ($allowed -notcontains $signerSha) {
    Fail "APK novo nao usa o signer fisicamente pinado; publicacao bloqueada."
}
$apkSha = (Get-FileHash -LiteralPath $namedApk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host ("Signer SHA256: " + $signerSha)
Write-Host ("APK SHA256   : " + $apkSha)

$releaseExists = Try-Native $gh @("release", "view", $tag, "--repo", $OtaRepository)
if ($releaseExists -eq 0) {
    Fail ("Release OTA ja existe: " + $tag)
}
Invoke-Native $gh @(
    "release", "create", $tag, $namedApk,
    "--repo", $OtaRepository,
    "--title", ("PocketPC " + $versionName + " (Home Test)"),
    "--notes", ("Signed PocketPC home-test OTA from private source revision " + $head + ". DEVELOPMENT / NOT PHYSICAL-PASS."),
    "--prerelease"
)

$apkUrl = "https://github.com/" + $OtaRepository + "/releases/download/" + $tag + "/" + [IO.Path]::GetFileName($namedApk)
$tempRoot = Join-Path $env:TEMP ("pocketpc-home-ota-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
try {
    $feedPath = Join-Path $tempRoot "latest.json"
    $prepare = Join-Path $RepoRoot "scripts\prepare-update-feed.py"
    Invoke-Native $python.File ($python.Prefix + @(
        $prepare,
        "--apk", $namedApk,
        "--apk-url", $apkUrl,
        "--source-revision", $head,
        "--version-code", [string]$versionCode,
        "--version-name", $versionName,
        "--channel", "development",
        "--notes", ("Alpha 22 home-test from private source revision " + $head),
        "--output", $feedPath,
        "--publish"
    ))

    $otaClone = Join-Path $tempRoot "ota-repo"
    Invoke-Native $gh @("repo", "clone", $OtaRepository, $otaClone, "--", "--depth", "1")
    Copy-Item -LiteralPath $feedPath -Destination (Join-Path $otaClone "latest.json") -Force
    Invoke-Native $git @("-C", $otaClone, "config", "user.name", "PocketPC Home OTA")
    Invoke-Native $git @("-C", $otaClone, "config", "user.email", "pocketpc-home-ota@users.noreply.github.com")
    Invoke-Native $git @("-C", $otaClone, "add", "latest.json")
    $staged = Invoke-Capture $git @("-C", $otaClone, "diff", "--cached", "--name-only")
    if ([string]::IsNullOrWhiteSpace($staged)) { Fail "latest.json nao mudou." }
    Invoke-Native $git @("-C", $otaClone, "commit", "-m", ("publish: PocketPC " + $versionName))
    Invoke-Native $git @("-C", $otaClone, "push", "origin", "HEAD:main")
}
finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$feedPublicUrl = "https://raw.githubusercontent.com/" + $OtaRepository + "/main/latest.json"
Write-Host "PUBLIC OTA FEED: PUBLISHED" -ForegroundColor Green
Write-Host ("Feed : " + $feedPublicUrl)
Write-Host ("APK  : " + $apkUrl)

if ($InstallNow) {
    $adb = Join-Path $AndroidSdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $adb)) { Fail "adb.exe nao foi encontrado." }
    $devices = Invoke-Capture $adb @("devices")
    $deviceLines = @($devices -split "`r?`n" | Where-Object { $_ -match "\sdevice$" })
    if ($deviceLines.Count -ne 1) {
        Fail ("Esperado exatamente 1 aparelho ADB autorizado; encontrados: " + $deviceLines.Count)
    }
    Write-Host "Instalando bootstrap OTA-capable sem apagar dados..."
    Invoke-Native $adb @("install", "-r", $namedApk)
    Write-Host "ADB INSTALL -r: PASS" -ForegroundColor Green
    Write-Host "Abra o PocketPC e entre em Atualizacoes para o primeiro teste do feed publico."
}

Write-Host "POCKETPC_HOME_OTA_BOOTSTRAP_OK" -ForegroundColor Green
Write-Host "A partir desta build, o app verifica o feed automaticamente e mantem validacao de SHA, revision e assinatura."
