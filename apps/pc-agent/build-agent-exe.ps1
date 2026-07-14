param(
  [switch] $NoSyncWebDownload
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent (Split-Path -Parent $Root)
$Dist = Join-Path $Root "dist"
$Work = Join-Path $Root "build"
$Assets = Join-Path $Root "assets"
$Script = Join-Path $Root "buildgraph_agent.py"
$Icon = Join-Path $Assets "specup-agent.ico"
$WebDownloadDir = Join-Path $RepoRoot "apps\web\public\downloads\pc-agent"
$VenvPython = Join-Path $RepoRoot ".venv\Scripts\python.exe"
$Python = "python"
if (Test-Path $VenvPython) {
  $Python = $VenvPython
}

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Command,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $CommandArgs
  )

  & $Command @CommandArgs
  if ($LASTEXITCODE -ne 0) {
    throw "command failed with exit code ${LASTEXITCODE}: $Command $($CommandArgs -join ' ')"
  }
}

Invoke-Checked $Python -m pip install -r (Join-Path $Root "requirements.txt") -r (Join-Path $Root "requirements-build.txt")

function Build-AgentExecutable {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Name,
    [switch] $Windowed
  )

  $Args = @(
    "--clean",
    "--noconfirm",
    "--onefile",
    "--name", $Name,
    "--exclude-module", "numpy",
    "--exclude-module", "scipy",
    "--exclude-module", "pandas",
    "--exclude-module", "matplotlib",
    "--exclude-module", "IPython",
    "--exclude-module", "jupyter",
    "--distpath", $Dist,
    "--workpath", (Join-Path $Work $Name)
  )
  if (Test-Path $Icon) {
    $Args += "--icon"
    $Args += $Icon
  }
  if (Test-Path $Assets) {
    $Args += "--add-data"
    $Args += "$Assets;assets"
  }
  if ($Windowed) {
    $Args += "--windowed"
  }
  $Args += $Script

  Invoke-Checked $Python -m PyInstaller @Args
}

function Get-AgentVersion {
  $Content = Get-Content -LiteralPath $Script -Raw
  $Match = [regex]::Match($Content, 'DEFAULT_AGENT_VERSION\s*=\s*"([^"]+)"')
  if (!$Match.Success) {
    throw "DEFAULT_AGENT_VERSION was not found in $Script"
  }
  return $Match.Groups[1].Value
}

function Write-Utf8NoBom {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Path,
    [Parameter(Mandatory = $true)]
    [string] $Content
  )

  $Encoding = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Content, $Encoding)
}

function Sync-WebDownload {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Exe,
    [Parameter(Mandatory = $true)]
    [string] $Version
  )

  if (!(Test-Path $WebDownloadDir)) {
    New-Item -ItemType Directory -Path $WebDownloadDir | Out-Null
  }

  $WebExe = Join-Path $WebDownloadDir "agent.exe"
  Copy-Item -LiteralPath $Exe -Destination $WebExe -Force
  $Hash = (Get-FileHash -LiteralPath $WebExe -Algorithm SHA256).Hash.ToLowerInvariant()
  $ManifestPath = Join-Path $WebDownloadDir "latest.json"
  $Manifest = [ordered]@{
    version = $Version
    fileName = "PCAgent.exe"
    downloadUrl = "agent.exe"
    sha256 = $Hash
    notes = "Local demo PCAgent build"
  }
  $ManifestJson = ($Manifest | ConvertTo-Json) + [Environment]::NewLine
  Write-Utf8NoBom -Path $ManifestPath -Content $ManifestJson

  Write-Host "synced $WebExe"
  Write-Host "updated $ManifestPath"
  Write-Host "version $Version sha256 $Hash"
}

$PythonBase = (& $Python -c "import sys; print(sys.base_prefix)").Trim()
if ($LASTEXITCODE -ne 0 -or !$PythonBase) {
  throw "failed to resolve the Python base prefix"
}
$TclVersion = (& $Python -c "import _tkinter; print(_tkinter.TCL_VERSION)").Trim()
$TkVersion = (& $Python -c "import _tkinter; print(_tkinter.TK_VERSION)").Trim()
if ($LASTEXITCODE -ne 0 -or !$TclVersion -or !$TkVersion) {
  throw "failed to resolve the Tcl/Tk runtime versions"
}
$TclPath = Join-Path $PythonBase "tcl\tcl$TclVersion"
$TkPath = Join-Path $PythonBase "tcl\tk$TkVersion"
if (!(Test-Path -LiteralPath $TclPath) -or !(Test-Path -LiteralPath $TkPath)) {
  throw "Tcl/Tk runtime files were not found under $PythonBase"
}

$PreviousLocation = Get-Location
$PreviousTclLibrary = [Environment]::GetEnvironmentVariable("TCL_LIBRARY", "Process")
$PreviousTkLibrary = [Environment]::GetEnvironmentVariable("TK_LIBRARY", "Process")
try {
  Set-Location -LiteralPath $RepoRoot
  # Tcl fails to normalize the absolute bundled-runtime path in some Windows
  # environments. Keep these paths relative while PyInstaller probes Tcl/Tk.
  $env:TCL_LIBRARY = Resolve-Path -Relative -LiteralPath $TclPath
  $env:TK_LIBRARY = Resolve-Path -Relative -LiteralPath $TkPath
  Invoke-Checked -Command $Python -CommandArgs @(
    "-c",
    "import tkinter; print(tkinter.Tcl())"
  )

  Build-AgentExecutable -Name "agent" -Windowed
  Build-AgentExecutable -Name "agent-cli"
}
finally {
  Set-Location -LiteralPath $PreviousLocation
  if ($null -eq $PreviousTclLibrary) {
    Remove-Item Env:TCL_LIBRARY -ErrorAction SilentlyContinue
  }
  else {
    $env:TCL_LIBRARY = $PreviousTclLibrary
  }
  if ($null -eq $PreviousTkLibrary) {
    Remove-Item Env:TK_LIBRARY -ErrorAction SilentlyContinue
  }
  else {
    $env:TK_LIBRARY = $PreviousTkLibrary
  }
}

$Exe = Join-Path $Dist "agent.exe"
if (!(Test-Path $Exe)) {
  throw "agent.exe was not created: $Exe"
}

$CliExe = Join-Path $Dist "agent-cli.exe"
if (!(Test-Path $CliExe)) {
  throw "agent-cli.exe was not created: $CliExe"
}

Write-Host "created $Exe"
Write-Host "created $CliExe"

if (!$NoSyncWebDownload) {
  Sync-WebDownload -Exe $Exe -Version (Get-AgentVersion)
}
