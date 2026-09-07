[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-PocketPcProcessArgument {
    param(
        [AllowEmptyString()]
        [Parameter(Mandatory=$true)]
        [string]$Value
    )

    if ($Value -notmatch '[\s"]') {
        return $Value
    }

    if ($Value.Contains('"')) {
        throw "Process argument contains an embedded quote, which is not supported by this Windows harness."
    }

    return '"' + $Value + '"'
}

function Invoke-PocketPcProcessCapture {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory=$true)][string]$Operation,
        [Parameter(Mandatory=$true)][int]$TimeoutSeconds,
        [switch]$AllowFailure,
        [string]$TimeoutGuidance
    )

    if ($TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 3600) {
        throw "TimeoutSeconds must be between 1 and 3600."
    }
    if (-not (Test-Path $FilePath -PathType Leaf)) {
        throw "Executable not found. operation=$Operation path=$FilePath"
    }

    $argumentLine = (
        @($Arguments | ForEach-Object {
            ConvertTo-PocketPcProcessArgument ([string]$_)
        }) -join " "
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = $argumentLine
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $stdoutTask = $null
    $stderrTask = $null

    try {
        if (-not $process.Start()) {
            throw "Failed to start $Operation."
        }

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()

        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch {}
            try { [void]$process.WaitForExit(5000) } catch {}

            $message = "$Operation exceeded the timeout of $TimeoutSeconds seconds."
            if (-not [string]::IsNullOrWhiteSpace($TimeoutGuidance)) {
                $message += " " + $TimeoutGuidance.Trim()
            }
            throw $message
        }

        $process.WaitForExit()
        $exitCode = [int]$process.ExitCode
        $stdoutText = if ($stdoutTask) {
            ([string]$stdoutTask.Result).Trim()
        } else {
            ""
        }
        $stderrText = if ($stderrTask) {
            ([string]$stderrTask.Result).Trim()
        } else {
            ""
        }

        $parts = New-Object System.Collections.Generic.List[string]
        if (-not [string]::IsNullOrWhiteSpace($stdoutText)) {
            $parts.Add($stdoutText)
        }
        if (-not [string]::IsNullOrWhiteSpace($stderrText)) {
            $parts.Add($stderrText)
        }
        $combinedText = ($parts -join [Environment]::NewLine).Trim()

        if ($exitCode -ne 0 -and -not $AllowFailure) {
            throw (
                "$Operation failed with exit code $exitCode." +
                [Environment]::NewLine +
                $combinedText
            )
        }

        return [pscustomobject]@{
            ExitCode = $exitCode
            Text = $combinedText
            Stdout = $stdoutText
            Stderr = $stderrText
        }
    }
    finally {
        if ($process) {
            $process.Dispose()
        }
    }
}
