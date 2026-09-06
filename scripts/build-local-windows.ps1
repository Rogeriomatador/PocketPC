[CmdletBinding()]
param(
    [string]$AndroidSdkRoot,
    [string]$JavaHome,
    [switch]$InstallMissingSdkComponents,
    [switch]$AcceptAndroidLicenses,
    [switch]$RequirePythonPolicyChecks,
    [switch]$AllowDirtyTree,
    [string]$OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildLockPath = Join-Path $repoRoot "toolchains\android-build-lock.json"
if (-not (Test-Path $buildLockPath -PathType Leaf)) {
    throw "Toolchain lock ausente: $buildLockPath"
}

$BuildLock = Get-Content $buildLockPath -Raw | ConvertFrom-Json
if ($BuildLock.schemaVersion -ne 1 -or $BuildLock.status -ne "PINNED") {
    throw "android-build-lock.json inválido ou não pinado."
}

$GradleVersion = [string]$BuildLock.gradle.version
$GradleBinSha256 = ([string]$BuildLock.gradle.distributionSha256).ToLowerInvariant()
$GradleUrl = [string]$BuildLock.gradle.distributionUrl
$JdkMajor = [int]$BuildLock.jdk.major
$CompileSdk = [int]$BuildLock.android.compileSdk
$BuildToolsVersion = [string]$BuildLock.android.buildTools
$NdkVersion = [string]$BuildLock.android.ndk
$CmakeVersion = [string]$BuildLock.android.cmake
$AgpVersion = [string]$BuildLock.plugins.androidGradlePlugin
$KotlinComposeVersion = [string]$BuildLock.plugins.kotlinComposePlugin
$AppPackageName = [string]$BuildLock.app.packageName
$AppVersionName = [string]$BuildLock.app.versionName
$AppVersionCode = [int]$BuildLock.app.versionCode
$DirtyEmbeddedRevision = [string]$BuildLock.evidence.dirtyTreeEmbeddedRevision

$RequiredAndroidComponents = [ordered]@{}
foreach ($component in $BuildLock.android.requiredComponents) {
    $name = [string]$component
    $relative = $name.Replace(";", [string][IO.Path]::DirectorySeparatorChar)
    $RequiredAndroidComponents[$name] = $relative
}

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Native {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $result = & $FilePath @Arguments 2>&1
    $exit = $LASTEXITCODE
    $text = ($result | Out-String).Trim()
    if ($exit -ne 0) {
        throw ("Command failed ($exit): $FilePath $($Arguments -join ' ')" +
            [Environment]::NewLine + $text)
    }
    return $text
}

function Resolve-JavaPinned {
    param([string]$ExplicitJavaHome)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($ExplicitJavaHome) { $candidates.Add($ExplicitJavaHome) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    if ($env:ProgramFiles) {
        $candidates.Add((Join-Path $env:ProgramFiles "Android\Android Studio\jbr"))
    }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr"))
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $java = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path $java -PathType Leaf)) { continue }

        $version = (& $java -version 2>&1 | Out-String)
        if ($version -match 'version\s+"(?<major>\d+)') {
            if ([int]$Matches.major -eq $JdkMajor) {
                return [pscustomobject]@{
                    Home = (Resolve-Path $candidate).Path
                    Java = (Resolve-Path $java).Path
                    VersionText = $version.Trim()
                }
            }
        }
    }

    throw @"
JDK $JdkMajor não encontrado.
Defina -JavaHome ou JAVA_HOME para um JDK $JdkMajor.
Se o Android Studio estiver instalado, o script também procura o JBR embutido.
"@
}

function Resolve-AndroidSdk {
    param([string]$ExplicitSdk)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($ExplicitSdk) { $candidates.Add($ExplicitSdk) }
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add($env:ANDROID_SDK_ROOT) }
    if ($env:ANDROID_HOME) { $candidates.Add($env:ANDROID_HOME) }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ($candidate -and (Test-Path $candidate -PathType Container)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw @"
Android SDK não encontrado.
Instale o Android SDK/Android Studio ou informe -AndroidSdkRoot.
"@
}

function Find-SdkManager {
    param([string]$SdkRoot)

    $latest = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (Test-Path $latest -PathType Leaf) {
        return (Resolve-Path $latest).Path
    }

    $toolsRoot = Join-Path $SdkRoot "cmdline-tools"
    if (Test-Path $toolsRoot -PathType Container) {
        $candidate = Get-ChildItem $toolsRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "bin\sdkmanager.bat" } |
            Where-Object { Test-Path $_ -PathType Leaf } |
            Select-Object -First 1

        if ($candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    $legacy = Join-Path $SdkRoot "tools\bin\sdkmanager.bat"
    if (Test-Path $legacy -PathType Leaf) {
        return (Resolve-Path $legacy).Path
    }

    return $null
}

function Get-MissingAndroidComponents {
    param([string]$SdkRoot)

    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $RequiredAndroidComponents.GetEnumerator()) {
        $path = Join-Path $SdkRoot $entry.Value
        if (-not (Test-Path $path)) {
            $missing.Add($entry.Key)
        }
    }
    return @($missing)
}

function Ensure-AndroidComponents {
    param([string]$SdkRoot)

    $missing = @(Get-MissingAndroidComponents $SdkRoot)
    if ($missing.Count -eq 0) {
        return
    }

    if (-not $InstallMissingSdkComponents) {
        $missingText = $missing -join [Environment]::NewLine
        throw @"
Android SDK incompleto. Componentes ausentes:
$missingText

Rode novamente com -InstallMissingSdkComponents.
Se as licenças ainda não estiverem aceitas, use também -AcceptAndroidLicenses.
"@
    }

    $sdkManager = Find-SdkManager $SdkRoot
    if (-not $sdkManager) {
        throw "sdkmanager.bat não encontrado no Android SDK."
    }

    if ($AcceptAndroidLicenses) {
        Write-Step "Aceitando licenças Android solicitadas pelo sdkmanager"
        (1..200 | ForEach-Object { "y" }) |
            & $sdkManager "--sdk_root=$SdkRoot" "--licenses"
        if ($LASTEXITCODE -ne 0) {
            throw "sdkmanager --licenses falhou com código $LASTEXITCODE."
        }
    }

    Write-Step "Instalando componentes Android ausentes"
    Invoke-Native $sdkManager (@("--sdk_root=$SdkRoot") + $missing)

    $stillMissing = @(Get-MissingAndroidComponents $SdkRoot)
    if ($stillMissing.Count -ne 0) {
        throw "Componentes Android continuam ausentes: $($stillMissing -join ', ')"
    }
}

function Ensure-Gradle {
    $toolBase = if ($env:LOCALAPPDATA) {
        Join-Path $env:LOCALAPPDATA "PocketPC\toolchains"
    } else {
        Join-Path $env:TEMP "PocketPC\toolchains"
    }

    New-Item -ItemType Directory -Force -Path $toolBase | Out-Null

    $zipPath = Join-Path $toolBase "gradle-$GradleVersion-bin.zip"
    $gradleHome = Join-Path $toolBase "gradle-$GradleVersion"
    $gradleBat = Join-Path $gradleHome "bin\gradle.bat"

    $needDownload = $true
    if (Test-Path $zipPath -PathType Leaf) {
        $hash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -eq $GradleBinSha256) {
            $needDownload = $false
        } else {
            Remove-Item -Force $zipPath
        }
    }

    if ($needDownload) {
        Write-Step "Baixando Gradle $GradleVersion com SHA-256 pinado"
        Invoke-WebRequest -Uri $GradleUrl -OutFile $zipPath
    }

    $actualHash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $GradleBinSha256) {
        Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
        throw "SHA-256 do Gradle divergiu. esperado=$GradleBinSha256 atual=$actualHash"
    }

    if (-not (Test-Path $gradleBat -PathType Leaf)) {
        Write-Step "Extraindo Gradle $GradleVersion"
        $tempExtract = Join-Path $toolBase ".gradle-extract-$([Guid]::NewGuid().ToString('N'))"
        New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
        try {
            Expand-Archive -Path $zipPath -DestinationPath $tempExtract -Force
            $extracted = Join-Path $tempExtract "gradle-$GradleVersion"
            if (-not (Test-Path $extracted -PathType Container)) {
                throw "Estrutura inesperada no ZIP do Gradle."
            }
            if (Test-Path $gradleHome) {
                Remove-Item -Recurse -Force $gradleHome
            }
            Move-Item $extracted $gradleHome
        }
        finally {
            Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
        }
    }

    if (-not (Test-Path $gradleBat -PathType Leaf)) {
        throw "gradle.bat não encontrado após extração."
    }

    return [pscustomobject]@{
        Home = $gradleHome
        Executable = $gradleBat
        DistributionSha256 = $actualHash
    }
}

function Invoke-PythonPolicyChecks {
    param([string]$RepoRoot)

    $python = $null
    $prefixArgs = @()

    $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $python = $pythonCommand.Source
    } else {
        $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
        if ($pythonCommand) {
            $python = $pythonCommand.Source
        } else {
            $py = Get-Command py.exe -ErrorAction SilentlyContinue
            if ($py) {
                $python = $py.Source
                $prefixArgs = @("-3")
            }
        }
    }

    if (-not $python) {
        if ($RequirePythonPolicyChecks) {
            throw "Python 3 não encontrado, mas -RequirePythonPolicyChecks foi solicitado."
        }
        return "SKIPPED_NO_PYTHON"
    }

    try {
        $pythonVersion = Invoke-NativeCapture $python ($prefixArgs + @("--version"))
        if ($pythonVersion -notmatch 'Python\s+3[.]') {
            throw "Comando encontrado não é Python 3: $pythonVersion"
        }
    }
    catch {
        if ($RequirePythonPolicyChecks) {
            throw
        }
        return "SKIPPED_NO_PYTHON"
    }

    $scripts = @(
        "scripts\verify-android-build-lock.py",
        "scripts\test-proot-artifact-policy.py",
        "scripts\verify-proot-approval.py",
        "scripts\test-device-evidence-bundle-verifier.py",
        "scripts\test-local-build-record-verifier.py",
        "scripts\test-device-install-record-verifier.py",
        "scripts\test-device-chain-verifier.py",
        "scripts\test-physical-validation-record-verifier.py",
        "scripts\test-first-physical-test-record-verifier.py",
        "scripts\test-preflight-record-verifier.py",
        "scripts\test-failure-triage-verifier.py",
        "scripts\test-powershell51-compat.py"
    )

    foreach ($relative in $scripts) {
        Write-Step "Policy check: $relative"
        Invoke-Native $python ($prefixArgs + @(Join-Path $RepoRoot $relative))
    }

    return "PASS"
}

function Assert-NoUnapprovedSubstrateBinaries {
    param([string]$RepoRoot)

    $roots = @(
        (Join-Path $RepoRoot "app\src"),
        (Join-Path $RepoRoot "third_party")
    )

    $matches = Get-ChildItem $roots -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -like "libproot*.so" -or
            $_.Name -like "libtalloc.so*" -or
            $_.Name -eq "libandroid-shmem.so"
        }

    if ($matches) {
        $matchText = $matches.FullName -join [Environment]::NewLine
        throw ("Binários PRoot não aprovados encontrados:" +
            [Environment]::NewLine + $matchText)
    }
}

function Inspect-Apk {
    param(
        [string]$ApkPath,
        [string]$ApkSigner
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })

        $required = @(
            "lib/arm64-v8a/libpocketpc_runtime.so",
            "lib/x86_64/libpocketpc_runtime.so",
            "assets/proot-substrate-approval.json",
            "assets/proot/LOCK.json",
            "assets/proot/ARTIFACT_CONTRACT.json"
        )

        foreach ($entry in $required) {
            if ($names -notcontains $entry) {
                throw "APK não contém entrada obrigatória: $entry"
            }
        }

        $forbidden = @(
            $names | Where-Object {
                $_ -match '(^|/)libproot(_loader)?\.so$' -or
                $_ -match '(^|/)libtalloc\.so([.][0-9]+)*$' -or
                $_ -match '(^|/)libandroid-shmem\.so$'
            }
        )
        if ($forbidden.Count -gt 0) {
            throw "APK contém substrate não aprovado: $($forbidden -join ', ')"
        }
    }
    finally {
        $zip.Dispose()
    }

    $signingText = Invoke-NativeCapture $ApkSigner @(
        "verify",
        "--print-certs",
        $ApkPath
    )

    $certs = @(
        [regex]::Matches(
            $signingText,
            'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
        ) | ForEach-Object {
            $_.Groups[1].Value.ToLowerInvariant()
        } | Select-Object -Unique
    )

    if ($certs.Count -eq 0) {
        throw "apksigner não retornou SHA-256 do certificado."
    }

    return [pscustomobject]@{
        SigningText = $signingText
        SigningCertificateSha256 = $certs
    }
}

Write-Step "Validando repositório Git"
$git = (Get-Command git -ErrorAction Stop).Source
$sourceCommit = (Invoke-NativeCapture $git @("-C", $repoRoot, "rev-parse", "HEAD")).Trim()
if ($sourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
    throw "git rev-parse HEAD não retornou um commit SHA-1 de 40 caracteres."
}

$dirtyText = Invoke-NativeCapture $git @(
    "-C",
    $repoRoot,
    "status",
    "--porcelain",
    "--untracked-files=all"
)
$sourceDirty = -not [string]::IsNullOrWhiteSpace($dirtyText)

if ($sourceDirty -and -not $AllowDirtyTree) {
    throw @"
Árvore Git contém alterações/untracked files.
Build reproduzível recusado.

$dirtyText

Faça commit/stash/limpeza ou use -AllowDirtyTree.
Com -AllowDirtyTree, o APK será marcado LOCAL_UNPINNED.
"@
}

$embeddedRevision = if ($sourceDirty) {
    $DirtyEmbeddedRevision
} else {
    $sourceCommit.ToLowerInvariant()
}

$shortCommit = $sourceCommit.Substring(0, 12).ToLowerInvariant()

Write-Step "Resolvendo JDK $JdkMajor"
$javaInfo = Resolve-JavaPinned $JavaHome

Write-Step "Resolvendo Android SDK"
$sdkRoot = Resolve-AndroidSdk $AndroidSdkRoot
Ensure-AndroidComponents $sdkRoot

Write-Step "Preparando Gradle pinado"
$gradleInfo = Ensure-Gradle
$gradleVersionText = Invoke-NativeCapture $gradleInfo.Executable @("--version")
if ($gradleVersionText -notmatch "Gradle\s+$([regex]::Escape($GradleVersion))") {
    throw "Gradle executado não reportou versão $GradleVersion."
}

Write-Step "Rejeitando substrate PRoot não aprovado"
Assert-NoUnapprovedSubstrateBinaries $repoRoot

Write-Step "Executando policy checks Python quando disponíveis"
$pythonPolicyState = Invoke-PythonPolicyChecks $repoRoot

$oldJavaHome = $env:JAVA_HOME
$oldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$oldAndroidHome = $env:ANDROID_HOME
$oldRevision = $env:POCKETPC_SOURCE_REVISION

try {
    $env:JAVA_HOME = $javaInfo.Home
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:POCKETPC_SOURCE_REVISION = $embeddedRevision

    Push-Location $repoRoot
    try {
        Write-Step "Executando unit tests"
        Invoke-Native $gradleInfo.Executable @(
            "--no-daemon",
            "--stacktrace",
            ":app:testDebugUnitTest"
        )

        Write-Step "Executando Android lint"
        Invoke-Native $gradleInfo.Executable @(
            "--no-daemon",
            "--stacktrace",
            ":app:lintDebug"
        )

        Write-Step "Montando APK debug"
        Invoke-Native $gradleInfo.Executable @(
            "--no-daemon",
            "--stacktrace",
            ":app:assembleDebug"
        )
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:ANDROID_SDK_ROOT = $oldAndroidSdkRoot
    $env:ANDROID_HOME = $oldAndroidHome
    $env:POCKETPC_SOURCE_REVISION = $oldRevision
}

$sourceApk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $sourceApk -PathType Leaf)) {
    throw "Gradle terminou sem gerar app-debug.apk."
}

$buildTools = Join-Path $sdkRoot "build-tools\$BuildToolsVersion"
$apkSigner = Join-Path $buildTools "apksigner.bat"
if (-not (Test-Path $apkSigner -PathType Leaf)) {
    throw "apksigner.bat não encontrado em Build Tools $BuildToolsVersion."
}

$apkInspection = Inspect-Apk $sourceApk $apkSigner

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot "local-build"
}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$buildName = "$AppVersionName-$shortCommit"
if ($sourceDirty) {
    $buildName += "-dirty"
}
$buildDir = Join-Path $OutputRoot $buildName
if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
}
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$apkName = "PocketPC-$AppVersionName-$shortCommit-debug.apk"
if ($sourceDirty) {
    $apkName = "PocketPC-$AppVersionName-$shortCommit-dirty-debug.apk"
}
$apkOut = Join-Path $buildDir $apkName
Copy-Item $sourceApk $apkOut

$apkHash = (Get-FileHash $apkOut -Algorithm SHA256).Hash.ToLowerInvariant()
$apkBytes = (Get-Item $apkOut).Length

"$apkHash  $apkName" |
    Set-Content -Path (Join-Path $buildDir "$apkName.sha256") -Encoding ASCII

$apkInspection.SigningText |
    Set-Content -Path (Join-Path $buildDir "apk-signing.txt") -Encoding UTF8

$classification = if ($sourceDirty) {
    "LOCAL_BUILD_DIRTY_UNPINNED"
} elseif ($pythonPolicyState -eq "PASS") {
    "LOCAL_BUILD_POLICY_CHECKED"
} else {
    "LOCAL_BUILD_PYTHON_POLICY_SKIPPED"
}

$record = [ordered]@{
    schemaVersion = 1
    classification = $classification
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    source = [ordered]@{
        commit = $sourceCommit.ToLowerInvariant()
        dirty = $sourceDirty
        embeddedRevision = $embeddedRevision
    }
    app = [ordered]@{
        packageName = $AppPackageName
        versionName = $AppVersionName
        versionCode = $AppVersionCode
    }
    toolchain = [ordered]@{
        javaHome = $javaInfo.Home
        javaVersion = $javaInfo.VersionText
        gradleVersion = $GradleVersion
        gradleDistributionSha256 = $gradleInfo.DistributionSha256
        androidSdkRoot = $sdkRoot
        compileSdk = $CompileSdk
        buildTools = $BuildToolsVersion
        ndk = $NdkVersion
        cmake = $CmakeVersion
        androidGradlePlugin = $AgpVersion
        kotlinComposePlugin = $KotlinComposeVersion
    }
    checks = [ordered]@{
        pythonPolicyChecks = $pythonPolicyState
        unitTests = "PASS"
        lint = "PASS"
        assembleDebug = "PASS"
        apkStructure = "PASS"
        unapprovedSubstrateRejected = "PASS"
    }
    apk = [ordered]@{
        fileName = $apkName
        bytes = $apkBytes
        sha256 = $apkHash
        signingCertificateSha256 = @($apkInspection.SigningCertificateSha256)
    }
}

$record |
    ConvertTo-Json -Depth 10 |
    Set-Content -Path (Join-Path $buildDir "local-build-record.json") -Encoding UTF8

Write-Host ""
Write-Host "PocketPC local build concluído." -ForegroundColor Green
Write-Host "Classification : $classification"
Write-Host "Source commit  : $sourceCommit"
Write-Host "Embedded rev   : $embeddedRevision"
Write-Host "APK            : $apkOut"
Write-Host "APK SHA-256    : $apkHash"
Write-Host "Build record   : $(Join-Path $buildDir 'local-build-record.json')"
