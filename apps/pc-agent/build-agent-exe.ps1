$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Dist = Join-Path $Root "dist"
$Work = Join-Path $Root "build"

python -m pip install -r (Join-Path $Root "requirements.txt") -r (Join-Path $Root "requirements-build.txt")

python -m PyInstaller `
  --clean `
  --noconfirm `
  --onefile `
  --name agent `
  --distpath $Dist `
  --workpath $Work `
  (Join-Path $Root "buildgraph_agent.py")

$Exe = Join-Path $Dist "agent.exe"
if (!(Test-Path $Exe)) {
  throw "agent.exe was not created: $Exe"
}

Write-Host "created $Exe"
