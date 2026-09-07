param(
    [string]$Repository = "Rogeriomatador/PocketPC",
    [string]$AndroidSdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$KeystorePath = "$env:USERPROFILE\.android\debug.keystore",
    [string]$BackupDirectory = "$env:USERPROFILE\Documents\PocketPC-Signing-Backup",
    [switch]$TriggerPublish
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

function Require-Command {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Comando obrigatório não encontrado: $Name"
    }
    return $command.Source
}

function Require-File {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label não encontrado: $Path"
    }
}

function Get-AuthorizedAdbSerial {
    param([string]$Adb)

    $rows = & $Adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices falhou."
    }

    $serials = @()
    foreach ($row in $rows) {
        if ($row -match "^([^\s]+)\s+device$") {
            $serials += $matches[1]
        }
    }

    if ($serials.Count -eq 0) {
        throw "Nenhum aparelho ADB autorizado foi encontrado."
    }
    if ($serials.Count -gt 1) {
        throw "Mais de um aparelho ADB autorizado foi encontrado. Deixe apenas o telefone do PocketPC conectado."
    }

    return $serials[0]
}

function Get-ApkCertificateSha256 {
    param([string]$ApkSigner, [string]$ApkPath)

    $output = & $ApkSigner verify --print-certs $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner não conseguiu verificar o APK instalado."
    }

    foreach ($line in $output) {
        if ($line -match "certificate SHA-256 digest:\s*([0-9a-fA-F]{64})") {
            return $matches[1].ToLowerInvariant()
        }
    }

    throw "Não foi possível extrair o SHA-256 do certificado do APK."
}

function Get-KeystoreCertificateSha256 {
    param(
        [string]$Keytool,
        [string]$Keystore,
        [string]$TempDirectory
    )

    $certificate = Join-Path $TempDirectory "bootstrap-cert.der"
    & $Keytool -exportcert -alias "androiddebugkey" -keystore $Keystore -storepass "android" -file $certificate | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "keytool não conseguiu exportar o certificado androiddebugkey."
    }

    return (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash.ToLowerInvariant()
}

Write-Host "PocketPC update-signing bootstrap" -ForegroundColor Cyan
Write-Host "Classification : LOCAL_SIGNING_BOOTSTRAP_NOT_A_RELEASE"
Write-Host ""

Require-File -Path $KeystorePath -Label "Keystore de bootstrap"

$adb = Join-Path $AndroidSdkRoot "platform-tools\adb.exe"
$apkSigner = Join-Path $AndroidSdkRoot "build-tools\36.0.0\apksigner.bat"

Require-File -Path $adb -Label "adb"
Require-File -Path $apkSigner -Label "apksigner"

$keytool = Require-Command "keytool"
$gh = Require-Command "gh"

& $gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI não está autenticado. Execute gh auth login uma vez e rode este script novamente."
}

$serial = Get-AuthorizedAdbSerial -Adb $adb
$temp = Join-Path $env:TEMP ("pocketpc-signing-bootstrap-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $temp | Out-Null

try {
    Write-Host "Verificando assinatura do PocketPC instalado..." -ForegroundColor Yellow

    $packageRows = & $adb -s $serial shell pm path dev.pocketpc.core
    if ($LASTEXITCODE -ne 0) {
        throw "Não foi possível localizar dev.pocketpc.core no aparelho."
    }

    $baseApk = $null
    foreach ($row in $packageRows) {
        if ($row -match "^package:(.+/base\.apk)\s*$") {
            $baseApk = $matches[1]
            break
        }
    }

    if ([string]::IsNullOrWhiteSpace($baseApk)) {
        throw "base.apk do PocketPC instalado não foi encontrado."
    }

    $pulledApk = Join-Path $temp "installed-pocketpc.apk"
    & $adb -s $serial pull $baseApk $pulledApk | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao copiar o APK instalado para verificação local."
    }

    $installedDigest = Get-ApkCertificateSha256 -ApkSigner $apkSigner -ApkPath $pulledApk
    $keystoreDigest = Get-KeystoreCertificateSha256 -Keytool $keytool -Keystore $KeystorePath -TempDirectory $temp

    Write-Host ("Installed signer SHA-256 : " + $installedDigest)
    Write-Host ("Local keystore SHA-256    : " + $keystoreDigest)

    if ($installedDigest -ne $keystoreDigest) {
        throw "A keystore local NÃO corresponde à assinatura do PocketPC instalado. Nenhum secret foi alterado."
    }

    Write-Host "Assinatura compatível confirmada." -ForegroundColor Green

    New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
    $backupPath = Join-Path $BackupDirectory "PocketPC-bootstrap-signing.jks"

    if (-not (Test-Path -LiteralPath $backupPath)) {
        Copy-Item -LiteralPath $KeystorePath -Destination $backupPath
    }

    $fingerprintPath = Join-Path $BackupDirectory "PocketPC-bootstrap-signing-sha256.txt"
    Set-Content -LiteralPath $fingerprintPath -Value $keystoreDigest -Encoding ASCII

    Write-Host "Configurando GitHub Secrets sem imprimir a chave..." -ForegroundColor Yellow

    $keystoreBytes = [System.IO.File]::ReadAllBytes($KeystorePath)
    $keystoreBase64 = [Convert]::ToBase64String($keystoreBytes)

    $keystoreBase64 | & $gh secret set POCKETPC_SIGNING_KEYSTORE_BASE64 --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao configurar POCKETPC_SIGNING_KEYSTORE_BASE64."
    }

    "android" | & $gh secret set POCKETPC_SIGNING_STORE_PASSWORD --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao configurar POCKETPC_SIGNING_STORE_PASSWORD."
    }

    "androiddebugkey" | & $gh secret set POCKETPC_SIGNING_KEY_ALIAS --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao configurar POCKETPC_SIGNING_KEY_ALIAS."
    }

    "android" | & $gh secret set POCKETPC_SIGNING_KEY_PASSWORD --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao configurar POCKETPC_SIGNING_KEY_PASSWORD."
    }

    $keystoreBase64 = $null
    $keystoreBytes = $null

    Write-Host ""
    Write-Host "POCKETPC_UPDATE_SIGNING_BOOTSTRAP_OK" -ForegroundColor Green
    Write-Host ("Repository : " + $Repository)
    Write-Host ("Signer SHA : " + $keystoreDigest)
    Write-Host ("Backup     : " + $backupPath)
    Write-Host "Private key content was not printed."

    if ($TriggerPublish) {
        Write-Host ""
        Write-Host "Disparando publish-update.yml..." -ForegroundColor Yellow
        & $gh workflow run "publish-update.yml" --repo $Repository
        if ($LASTEXITCODE -ne 0) {
            throw "Falha ao disparar publish-update.yml."
        }
        Write-Host "Workflow solicitado. Isso ainda NÃO significa release PASS."
    }
}
finally {
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
