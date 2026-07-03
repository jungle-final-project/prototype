$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Dist = Join-Path $Root "dist"
$Work = Join-Path $Root "build"
$Script = Join-Path $Root "buildgraph_agent.py"

python -m pip install -r (Join-Path $Root "requirements.txt") -r (Join-Path $Root "requirements-build.txt")

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
  if ($Windowed) {
    $Args += "--windowed"
  }
  $Args += $Script

  python -m PyInstaller @Args
}

Build-AgentExecutable -Name "agent" -Windowed
Build-AgentExecutable -Name "agent-cli"

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
