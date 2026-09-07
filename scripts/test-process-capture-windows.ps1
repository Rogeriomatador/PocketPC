[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$helper = Join-Path $PSScriptRoot "process-capture-windows.ps1"
if (-not (Test-Path $helper -PathType Leaf)) {
    throw "process-capture-windows.ps1 not found."
}
. $helper

$cmd = if ($env:ComSpec -and (Test-Path $env:ComSpec -PathType Leaf)) {
    $env:ComSpec
} else {
    (Get-Command cmd.exe -ErrorAction Stop).Source
}

$successArgs = @{
    FilePath = $cmd
    Arguments = @("/d", "/c", "echo POCKETPC_CAPTURE_OK")
    Operation = "process selftest success"
    TimeoutSeconds = 10
}
$success = Invoke-PocketPcProcessCapture @successArgs
if ($success.ExitCode -ne 0) {
    throw "Success probe returned a non-zero exit code."
}
if ($success.Text -notmatch 'POCKETPC_CAPTURE_OK') {
    throw "Success probe did not capture stdout."
}

$emptyArgs = @{
    FilePath = $cmd
    Arguments = @("/d", "/c", "exit /b 0")
    Operation = "process selftest empty stdout"
    TimeoutSeconds = 10
}
$empty = Invoke-PocketPcProcessCapture @emptyArgs
if ($empty.ExitCode -ne 0) {
    throw "Empty-output probe returned a non-zero exit code."
}
if ($null -eq $empty.Text) {
    throw "Empty-output probe returned null text."
}

$failureArgs = @{
    FilePath = $cmd
    Arguments = @("/d", "/c", "exit /b 7")
    Operation = "process selftest failure"
    TimeoutSeconds = 10
    AllowFailure = $true
}
$failure = Invoke-PocketPcProcessCapture @failureArgs
if ($failure.ExitCode -ne 7) {
    throw "Failure probe did not preserve exit code 7."
}

$powerShellExe = (Get-Process -Id $PID).Path
if (-not (Test-Path $powerShellExe -PathType Leaf)) {
    $powerShellExe = (Get-Command powershell.exe -ErrorAction Stop).Source
}

$timeoutObserved = $false
$timeoutArgs = @{
    FilePath = $powerShellExe
    Arguments = @("-NoProfile", "-Command", "Start-Sleep -Seconds 3")
    Operation = "process selftest timeout"
    TimeoutSeconds = 1
}
try {
    Invoke-PocketPcProcessCapture @timeoutArgs | Out-Null
}
catch {
    if ($_.Exception.Message -match 'exceeded the timeout') {
        $timeoutObserved = $true
    }
    else {
        throw
    }
}
if (-not $timeoutObserved) {
    throw "Timeout probe did not time out."
}

Write-Host "POCKETPC_PROCESS_CAPTURE_SELFTEST_OK"
Write-Host "success_exit=0"
Write-Host "failure_exit=7"
Write-Host "empty_output_nonnull=true"
Write-Host "timeout=true"
