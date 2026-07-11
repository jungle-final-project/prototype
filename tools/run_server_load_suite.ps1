[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:18082",
    [ValidateSet("load", "stress", "spike", "soak", "capacity")]
    [string[]]$Profiles = @("load", "stress", "spike", "soak", "capacity"),
    [string]$UserEmail = "user@example.com",
    [string]$UserPassword = "passw0rd!",
    [string]$SoakDuration = "1h",
    [int]$SoakVus = 20,
    [string]$K6Image = "grafana/k6:0.54.0",
    [string]$RunId,
    [string]$ApiLogPath
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$shortCommit = (& git -C $repoRoot rev-parse --short HEAD).Trim()
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "{0}KST-{1}" -f (Get-Date -Format "yyyyMMdd'T'HHmmss"), $shortCommit
}

$runDir = Join-Path $repoRoot "infra\k6\reports\runs\$RunId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$manifest = [ordered]@{
    runId = $RunId
    startedAt = (Get-Date).ToString("o")
    endedAt = $null
    commit = (& git -C $repoRoot rev-parse HEAD).Trim()
    branch = (& git -C $repoRoot branch --show-current).Trim()
    baseUrl = $BaseUrl
    k6Image = $K6Image
    profiles = @($Profiles)
    soakDuration = $SoakDuration
    soakVus = $SoakVus
    host = $env:COMPUTERNAME
    results = @()
}

function Write-Manifest {
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $runDir "manifest.json") -Encoding utf8
}

function Start-ResourceMonitor {
    param([string]$Profile)

    if ($Profile -ne "soak") {
        return $null
    }
    try {
        $uri = [Uri]$BaseUrl
        $connection = Get-NetTCPConnection -LocalPort $uri.Port -State Listen -ErrorAction Stop | Select-Object -First 1
        $processId = $connection.OwningProcess
    } catch {
        return $null
    }

    $csvPath = Join-Path $runDir "server-soak-resources.csv"
    $stopPath = Join-Path $runDir ".stop-soak-monitor"
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    "timestamp,workingSetMb,privateMb,cpuSeconds" | Set-Content -Path $csvPath -Encoding utf8
    return Start-Job -ArgumentList $processId,$csvPath,$stopPath -ScriptBlock {
        param($monitoredProcessId, $outputPath, $sentinelPath)
        while (-not (Test-Path -LiteralPath $sentinelPath)) {
            $process = Get-Process -Id $monitoredProcessId -ErrorAction SilentlyContinue
            if ($process) {
                "$(Get-Date -Format o),$([math]::Round($process.WorkingSet64 / 1MB, 2)),$([math]::Round($process.PrivateMemorySize64 / 1MB, 2)),$([math]::Round($process.CPU, 2))" |
                    Add-Content -Path $outputPath -Encoding utf8
            }
            Start-Sleep -Seconds 60
        }
    }
}

function Stop-ResourceMonitor {
    param($Job)

    if ($null -eq $Job) {
        return
    }
    New-Item -ItemType File -Force -Path (Join-Path $runDir ".stop-soak-monitor") | Out-Null
    Wait-Job $Job -Timeout 65 | Out-Null
    Stop-Job $Job -ErrorAction SilentlyContinue
    Remove-Job $Job -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $runDir ".stop-soak-monitor") -Force -ErrorAction SilentlyContinue
}

Write-Manifest
foreach ($profile in $Profiles) {
    $profileStartedAt = Get-Date
    $summaryRelative = "infra/k6/reports/runs/$RunId/server-$profile.json"
    $consoleLog = Join-Path $runDir "server-$profile.console.log"
    $monitorJob = Start-ResourceMonitor -Profile $profile

    $dockerArgs = @(
        "run", "--rm",
        "-e", "TEST_TYPE=$profile",
        "-e", "BASE_URL=$BaseUrl",
        "-e", "USER_EMAIL=$UserEmail",
        "-e", "USER_PASSWORD=$UserPassword",
        "-e", "THINK_TIME_SECONDS=0.2",
        "-e", "SUMMARY_PATH=/work/$summaryRelative",
        "-e", "SOAK_DURATION=$SoakDuration",
        "-e", "SOAK_VUS=$SoakVus",
        "-e", "SOAK_WINDOW_MINUTES=5",
        "-e", "SOAK_WINDOW_COUNT=12",
        "-v", "${repoRoot}:/work",
        "-w", "/work",
        $K6Image,
        "run", "--quiet", "infra/k6/server-workload.js"
    )

    & docker @dockerArgs 2>&1 | Tee-Object -FilePath $consoleLog
    $exitCode = $LASTEXITCODE
    Stop-ResourceMonitor -Job $monitorJob
    $health = "UNKNOWN"
    try {
        $healthResponse = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 5
        $health = $healthResponse.status
    } catch {
        $health = "DOWN"
    }
    $manifest.results += [ordered]@{
        profile = $profile
        startedAt = $profileStartedAt.ToString("o")
        endedAt = (Get-Date).ToString("o")
        exitCode = $exitCode
        healthAfter = $health
        summary = "server-$profile.json"
        consoleLog = "server-$profile.console.log"
    }
    Write-Manifest
}

if ($ApiLogPath -and (Test-Path -LiteralPath $ApiLogPath)) {
    Copy-Item -LiteralPath $ApiLogPath -Destination (Join-Path $runDir "api.log")
}
$manifest.endedAt = (Get-Date).ToString("o")
Write-Manifest
Write-Output $runDir
