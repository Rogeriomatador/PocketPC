param(
    [string]$Repository = "Rogeriomatador/PocketPC",
    [string]$RepoRoot = "",
    [string]$AndroidSdkRoot = "",
    [string]$JavaHome = "",
    [string]$KeystorePath = "",
    [string]$StorePassword = "android",
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeyPassword = "android",
    [switch]$SkipDebugValidation
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    throw ("POCKETPC_LOCAL_UPDATE_PUBLISH_FAILED: " + $Message)
}

function Invoke-Native {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @Arguments
        $nativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($nativeExitCode -ne 0) {
        Fail (
            "Command failed (" +
            $nativeExitCode +
            "): " +
            $FilePath +
            " " +
            ($Arguments -join " ")
        )
    }
}

function Invoke-NativeCaptureStdout {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $nativeOutput = & $FilePath @Arguments | Out-String
        $nativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($nativeExitCode -ne 0) {
        Fail (
            "Command failed (" +
            $nativeExitCode +
            "): " +
            $FilePath +
            " " +
            ($Arguments -join " ")
        )
    }

    return $nativeOutput.Trim()
}

function Resolve-Python {
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($python) {
        return [pscustomobject]@{
            File = $python.Source
            Prefix = @()
        }
    }

    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
        return [pscustomobject]@{
            File = $python.Source
            Prefix = @()
        }
    }

    $py = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($py) {
        return [pscustomobject]@{
            File = $py.Source
            Prefix = @("-3")
        }
    }

    Fail "Python 3 nao foi encontrado."
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Split-Path -Parent $PSScriptRoot
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$git = Get-Command git.exe -ErrorAction SilentlyContinue
if (-not $git) {
    $git = Get-Command git -ErrorAction SilentlyContinue
}
if (-not $git) {
    Fail "git nao foi encontrado."
}

$gh = Get-Command gh.exe -ErrorAction SilentlyContinue
if (-not $gh) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
}
if (-not $gh) {
    Fail "GitHub CLI (gh) nao foi encontrado."
}

$python = Resolve-Python

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $AndroidSdkRoot = $env:ANDROID_SDK_ROOT
    } elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
}
if (
    [string]::IsNullOrWhiteSpace($AndroidSdkRoot) -or
    -not (Test-Path -LiteralPath $AndroidSdkRoot)
) {
    Fail "Android SDK nao foi encontrado."
}
$AndroidSdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JavaHome = $env:JAVA_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidate = Join-Path $env:LOCALAPPDATA "PocketPC\toolchains\temurin17"
        if (Test-Path -LiteralPath $candidate) {
            $JavaHome = $candidate
        }
    }
}
if (
    [string]::IsNullOrWhiteSpace($JavaHome) -or
    -not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))
) {
    Fail "JDK 17 do PocketPC nao foi encontrado."
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $env:USERPROFILE ".android\debug.keystore"
}
if (-not (Test-Path -LiteralPath $KeystorePath)) {
    Fail ("Keystore nao encontrada: " + $KeystorePath)
}
$KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path

Write-Host "PocketPC local update publisher"
Write-Host ("Repository : " + $Repository)
Write-Host ("RepoRoot   : " + $RepoRoot)

$status = Invoke-NativeCaptureStdout $git.Source @(
    "-C", $RepoRoot, "status", "--porcelain"
)
if (-not [string]::IsNullOrWhiteSpace($status)) {
    Fail "Repositorio local possui alteracoes nao commitadas."
}

Invoke-Native $git.Source @(
    "-C", $RepoRoot, "pull", "--ff-only"
)

$head = Invoke-NativeCaptureStdout $git.Source @(
    "-C", $RepoRoot, "rev-parse", "HEAD"
)
if ($head -notmatch "^[0-9a-fA-F]{40}$") {
    Fail "Git HEAD invalido."
}
$head = $head.ToLowerInvariant()

$lockPath = Join-Path $RepoRoot "toolchains\android-build-lock.json"
$feedPath = Join-Path $RepoRoot "updates\stable.json"
$pinPath = Join-Path $RepoRoot "updates\bootstrap-signer.json"

$lock = Get-Content -LiteralPath $lockPath -Raw -Encoding UTF8 |
    ConvertFrom-Json
$feed = Get-Content -LiteralPath $feedPath -Raw -Encoding UTF8 |
    ConvertFrom-Json
$pin = Get-Content -LiteralPath $pinPath -Raw -Encoding UTF8 |
    ConvertFrom-Json

$versionName = [string]$lock.app.versionName
$versionCode = [int]$lock.app.versionCode

if (
    $feed.published -eq $true -and
    [int]$feed.versionCode -eq $versionCode -and
    [string]$feed.versionName -eq $versionName
) {
    Write-Host "LOCAL_UPDATE_PUBLISH_SKIPPED_ALREADY_CURRENT"
    exit 0
}

$bootstrap = Join-Path $RepoRoot "scripts\bootstrap-update-signing-windows.ps1"
& $bootstrap -Repository $Repository -RepoRoot $RepoRoot -KeystorePath $KeystorePath -StorePassword $StorePassword -KeyAlias $KeyAlias -KeyPassword $KeyPassword -ValidateOnly
if ($LASTEXITCODE -ne 0) {
    Fail "Bootstrap signer validation failed."
}

if (-not $SkipDebugValidation) {
    $buildScript = Join-Path $RepoRoot "scripts\build-local-windows.ps1"
    & $buildScript -AndroidSdkRoot $AndroidSdkRoot -JavaHome $JavaHome -RequirePythonPolicyChecks
    if ($LASTEXITCODE -ne 0) {
        Fail "Strict local debug validation failed."
    }
}

$gradleVersion = [string]$lock.gradle.version
if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    Fail "LOCALAPPDATA is required for the pinned Gradle location."
}
$gradleBat = Join-Path $env:LOCALAPPDATA (
    "PocketPC\toolchains\gradle-" +
    $gradleVersion +
    "\bin\gradle.bat"
)
if (-not (Test-Path -LiteralPath $gradleBat)) {
    Fail (
        "Pinned Gradle was not materialized by build-local-windows.ps1: " +
        $gradleBat
    )
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_SDK_ROOT = $AndroidSdkRoot
$env:ANDROID_HOME = $AndroidSdkRoot
$env:POCKETPC_SOURCE_REVISION = $head
$env:POCKETPC_SIGNING_STORE_FILE = $KeystorePath
$env:POCKETPC_SIGNING_STORE_PASSWORD = $StorePassword
$env:POCKETPC_SIGNING_KEY_ALIAS = $KeyAlias
$env:POCKETPC_SIGNING_KEY_PASSWORD = $KeyPassword

Invoke-Native $gradleBat @(
    "--no-daemon",
    ":app:testDebugUnitTest",
    ":app:lintDebug",
    ":app:assembleRelease"
)

$releaseDir = Join-Path $RepoRoot "app\build\outputs\apk\release"
$rawApk = Join-Path $releaseDir "app-release.apk"
if (-not (Test-Path -LiteralPath $rawApk)) {
    Fail "Release APK was not produced."
}

$namedApk = Join-Path $releaseDir (
    "PocketPC-" + $versionName + ".apk"
)
Copy-Item -LiteralPath $rawApk -Destination $namedApk -Force

$apksigner = Join-Path $AndroidSdkRoot (
    "build-tools\" +
    [string]$lock.android.buildTools +
    "\apksigner.bat"
)
if (-not (Test-Path -LiteralPath $apksigner)) {
    Fail "apksigner.bat was not found."
}

$signingText = Invoke-NativeCaptureStdout $apksigner @(
    "verify",
    "--print-certs",
    $namedApk
)

$signerMatch = [regex]::Match(
    $signingText,
    "Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})"
)
if (-not $signerMatch.Success) {
    Fail "Could not derive signer SHA-256 from the release APK."
}
$signerSha = $signerMatch.Groups[1].Value.ToLowerInvariant()

$allowed = @(
    $pin.allowedSigningCertificateSha256 |
        ForEach-Object {
            ([string]$_).Trim().ToLowerInvariant()
        }
)
if ($allowed -notcontains $signerSha) {
    Fail "Release APK signer differs from the physical Alpha 20 bootstrap signer."
}

$apkSha = (
    Get-FileHash -LiteralPath $namedApk -Algorithm SHA256
).Hash.ToLowerInvariant()

Write-Host ("Release signer : " + $signerSha)
Write-Host ("Release SHA256 : " + $apkSha)

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & $gh.Source auth status | Out-Host
    $authExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($authExitCode -ne 0) {
    Fail "GitHub CLI is not authenticated."
}

$tag = "v" + $versionName

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & $gh.Source release view $tag --repo $Repository | Out-Null
    $releaseExistsExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($releaseExistsExitCode -eq 0) {
    Fail ("Release tag already exists: " + $tag)
}

Invoke-Native $gh.Source @(
    "release",
    "create",
    $tag,
    $namedApk,
    "--repo",
    $Repository,
    "--target",
    $head,
    "--title",
    ("PocketPC " + $versionName),
    "--notes",
    ("Signed PocketPC update built from " + $head),
    "--prerelease"
)

$apkUrl =
    "https://github.com/" +
    $Repository +
    "/releases/download/" +
    $tag +
    "/" +
    [IO.Path]::GetFileName($namedApk)

$prepare = Join-Path $RepoRoot "scripts\prepare-update-feed.py"
$prepareArgs = @(
    $prepare,
    "--apk", $namedApk,
    "--apk-url", $apkUrl,
    "--source-revision", $head,
    "--notes", ("PocketPC " + $versionName),
    "--publish"
)
Invoke-Native $python.File ($python.Prefix + $prepareArgs)

$feedPolicy = Join-Path $RepoRoot "scripts\test-update-feed-policy.py"
Invoke-Native $python.File ($python.Prefix + @($feedPolicy))

Invoke-Native $git.Source @(
    "-C", $RepoRoot, "add", "updates/stable.json"
)

$staged = Invoke-NativeCaptureStdout $git.Source @(
    "-C", $RepoRoot, "diff", "--cached", "--name-only"
)
if ([string]::IsNullOrWhiteSpace($staged)) {
    Fail "Published feed did not change after release creation."
}

Invoke-Native $git.Source @(
    "-C", $RepoRoot,
    "commit",
    "-m",
    ("release: publish stable feed " + $versionName + " [skip ci]")
)
Invoke-Native $git.Source @(
    "-C", $RepoRoot,
    "push",
    "origin",
    "HEAD:main"
)

Write-Host "POCKETPC_LOCAL_UPDATE_PUBLISH_OK" -ForegroundColor Green
Write-Host ("Version    : " + $versionName)
Write-Host ("Source     : " + $head)
Write-Host ("APK        : " + $namedApk)
Write-Host ("APK SHA256 : " + $apkSha)
Write-Host ("Feed       : " + $apkUrl)
