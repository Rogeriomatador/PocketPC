param(
    [string]$Repository = "Rogeriomatador/PocketPC",
    [string]$RepoRoot = "",
    [string]$KeystorePath = "",
    [string]$StorePassword = "android",
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeyPassword = "android",
    [switch]$PublishNow
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    throw ("POCKETPC_UPDATE_BOOTSTRAP_FAILED: " + $Message)
}

function Resolve-Keytool {
    $candidates = @()

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe")
    }

    $command = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($command) {
        $candidates += $command.Source
    }

    foreach ($candidate in $candidates) {
        if (
            -not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path -LiteralPath $candidate)
        ) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    Fail "keytool.exe nao foi encontrado. Use o JDK 17 do PocketPC."
}

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Split-Path -Parent $PSScriptRoot
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $env:USERPROFILE ".android\debug.keystore"
}
if (-not (Test-Path -LiteralPath $KeystorePath)) {
    Fail ("Keystore nao encontrada em: " + $KeystorePath)
}
$KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path

$pinPath = Join-Path $RepoRoot "updates\bootstrap-signer.json"
if (-not (Test-Path -LiteralPath $pinPath)) {
    Fail ("Pin fisico de assinatura nao encontrado: " + $pinPath)
}

$pin = Get-Content -LiteralPath $pinPath -Raw -Encoding UTF8 | ConvertFrom-Json
$allowed = @(
    $pin.allowedSigningCertificateSha256 |
        ForEach-Object {
            ([string]$_).Trim().ToLowerInvariant()
        }
)

if ($allowed.Count -eq 0) {
    Fail "bootstrap-signer.json nao contem signer permitido."
}

$keytool = Resolve-Keytool
$tempCert = Join-Path $env:TEMP (
    "pocketpc-bootstrap-" + [Guid]::NewGuid().ToString("N") + ".cer"
)

try {
    $keytoolArgs = @(
        "-exportcert",
        "-alias", $KeyAlias,
        "-keystore", $KeystorePath,
        "-storepass", $StorePassword,
        "-file", $tempCert
    )
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $keytool @keytoolArgs | Out-Null
        $keytoolExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if (
        $keytoolExitCode -ne 0 -or
        -not (Test-Path -LiteralPath $tempCert)
    ) {
        Fail "Nao foi possivel exportar o certificado da keystore."
    }

    $candidate = (
        Get-FileHash -LiteralPath $tempCert -Algorithm SHA256
    ).Hash.ToLowerInvariant()

    Write-Host "PocketPC update signing bootstrap"
    Write-Host ("Repository       : " + $Repository)
    Write-Host ("Keystore         : " + $KeystorePath)
    Write-Host ("Alias            : " + $KeyAlias)
    Write-Host ("Signer SHA-256   : " + $candidate)
    Write-Host ("Bootstrap signer : " + $pin.bootstrapVersionName)

    if ($allowed -notcontains $candidate) {
        Fail (
            "A keystore nao corresponde ao APK Alpha 20 fisicamente " +
            "validado. Nenhum Secret foi enviado."
        )
    }

    Write-Host "Signer fisico: PASS" -ForegroundColor Green

    $gh = Get-Command gh.exe -ErrorAction SilentlyContinue
    if (-not $gh) {
        $gh = Get-Command gh -ErrorAction SilentlyContinue
    }
    if (-not $gh) {
        Fail (
            "GitHub CLI (gh) nao esta instalado. A assinatura foi validada, " +
            "mas os Secrets ainda nao foram configurados."
        )
    }

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
        Fail "GitHub CLI nao esta autenticado. Execute gh auth login uma vez."
    }

    $keystoreBase64 = [Convert]::ToBase64String(
        [IO.File]::ReadAllBytes($KeystorePath)
    )

    $secretValues = [ordered]@{
        "POCKETPC_SIGNING_KEYSTORE_BASE64" = $keystoreBase64
        "POCKETPC_SIGNING_STORE_PASSWORD" = $StorePassword
        "POCKETPC_SIGNING_KEY_ALIAS" = $KeyAlias
        "POCKETPC_SIGNING_KEY_PASSWORD" = $KeyPassword
    }

    foreach ($entry in $secretValues.GetEnumerator()) {
        $value = [string]$entry.Value
        $secretArgs = @(
            "secret", "set", $entry.Key,
            "--repo", $Repository,
            "--body", "-"
        )
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $value | & $gh.Source @secretArgs | Out-Host
            $secretExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        if ($secretExitCode -ne 0) {
            Fail ("Falha ao configurar o Secret " + $entry.Key + ".")
        }
    }

    Write-Host "GitHub signing Secrets: CONFIGURED" -ForegroundColor Green
    Write-Host (
        "A chave privada foi enviada apenas para GitHub Actions Secrets; " +
        "ela nao foi adicionada ao repositorio."
    )

    if ($PublishNow) {
        $workflowArgs = @(
            "workflow", "run", "publish-update.yml",
            "--repo", $Repository
        )
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $gh.Source @workflowArgs | Out-Host
            $workflowExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }

        if ($workflowExitCode -ne 0) {
            Fail "Falha ao disparar publish-update.yml."
        }

        Write-Host "Publisher Alpha 21: REQUESTED" -ForegroundColor Green
    } else {
        Write-Host (
            "Publisher ainda nao disparado. Rode este script novamente com " +
            "-PublishNow quando quiser publicar a versao do build lock."
        )
    }

    Write-Host "POCKETPC_UPDATE_BOOTSTRAP_OK" -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $tempCert -Force -ErrorAction SilentlyContinue
    Remove-Variable keystoreBase64 -ErrorAction SilentlyContinue
    Remove-Variable secretValues -ErrorAction SilentlyContinue
}
