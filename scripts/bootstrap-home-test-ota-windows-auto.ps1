param(
    [switch]$InstallNow,
    [switch]$SkipDebugValidation
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    throw ("POCKETPC_HOME_OTA_AUTO_BOOTSTRAP_FAILED: " + $Message)
}

function Resolve-CommandPath([string[]]$Names) {
    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    return $null
}

function Resolve-Gh {
    $gh = Resolve-CommandPath @("gh.exe", "gh")
    if ($gh) { return $gh }

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates += (Join-Path $env:ProgramFiles "GitHub CLI\gh.exe")
    }
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $candidates += (Join-Path ${env:ProgramFiles(x86)} "GitHub CLI\gh.exe")
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Invoke-Native {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        Fail ("Command failed (" + $LASTEXITCODE + "): " + $FilePath + " " + ($Arguments -join " "))
    }
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

$repoRoot = Split-Path -Parent $PSScriptRoot
$git = Resolve-CommandPath @("git.exe", "git")
if (-not $git) {
    Fail "git nao foi encontrado."
}

$gh = Resolve-Gh
if (-not $gh) {
    $winget = Resolve-CommandPath @("winget.exe", "winget")
    if (-not $winget) {
        Fail "GitHub CLI nao foi encontrado e winget tambem nao esta disponivel. Instale GitHub CLI uma vez e execute novamente."
    }

    Write-Host "GitHub CLI nao encontrado. Instalando via winget..." -ForegroundColor Yellow
    Invoke-Native $winget @(
        "install",
        "--id", "GitHub.cli",
        "--exact",
        "--source", "winget",
        "--accept-source-agreements",
        "--accept-package-agreements",
        "--silent"
    )

    $gh = Resolve-Gh
    if (-not $gh) {
        # winget may update PATH only for new shells; try the standard machine path explicitly.
        if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
            $candidate = Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"
            if (Test-Path -LiteralPath $candidate) {
                $gh = (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }
    if (-not $gh) {
        Fail "GitHub CLI foi instalado, mas gh.exe ainda nao foi localizado. Feche e abra o PowerShell e execute novamente."
    }

    Write-Host ("GitHub CLI instalado: " + $gh) -ForegroundColor Green
}

$authExit = Try-Native $gh @("auth", "status")
if ($authExit -ne 0) {
    Write-Host "GitHub CLI ainda nao esta autenticado." -ForegroundColor Yellow
    Write-Host "O GitHub vai abrir/instruir o login. Conclua a autenticacao e volte para esta janela." -ForegroundColor Yellow
    Invoke-Native $gh @(
        "auth", "login",
        "--hostname", "github.com",
        "--git-protocol", "https",
        "--web"
    )

    $authExit = Try-Native $gh @("auth", "status")
    if ($authExit -ne 0) {
        Fail "GitHub CLI continua sem autenticacao apos gh auth login."
    }
}

Write-Host "GitHub CLI: READY" -ForegroundColor Green

$ghDirectory = Split-Path -Parent $gh
$pathEntries = @(
    $env:Path -split ";"
) | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_)
}
if (
    $pathEntries -notcontains
        $ghDirectory
) {
    $env:Path =
        (
            @($env:Path, $ghDirectory) -
            join ";"
        )
    Write-Host (
        "GitHub CLI adicionado ao PATH desta sessao: " +
        $ghDirectory
    ) -ForegroundColor DarkGray
}

$bootstrap = Join-Path $PSScriptRoot "bootstrap-home-test-ota-windows.ps1"
if (-not (Test-Path -LiteralPath $bootstrap)) {
    Fail ("Bootstrap OTA principal nao encontrado: " + $bootstrap)
}

$args = @{}
if ($InstallNow) {
    $args["InstallNow"] = $true
}
if ($SkipDebugValidation) {
    $args["SkipDebugValidation"] = $true
}

& $bootstrap @args
