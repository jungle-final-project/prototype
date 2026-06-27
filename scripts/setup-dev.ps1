$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm was not found. Install Node.js 22 or use Dev Container."
}

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "python was not found. Install Python 3.11 or use Dev Container."
}

npm --prefix apps/web ci
Push-Location apps/web
npx playwright install chromium
Pop-Location

python -m venv .venv
$VenvPython = Join-Path $Root ".venv\Scripts\python.exe"

& $VenvPython -m pip install --upgrade pip
& $VenvPython -m pip install -r tools/requirements.txt -r apps/pc-agent/requirements.txt

& $VenvPython --version
Write-Host "Development dependencies installed."
