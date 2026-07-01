param(
    [string]$ApiBaseUrl = $env:API_BASE_URL,
    [string]$AdminEmail = $env:ADMIN_EMAIL,
    [string]$AdminPassword = $env:ADMIN_PASSWORD,
    [int]$Limit = 200
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) {
    $ApiBaseUrl = "http://localhost:8080"
}

if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    $AdminEmail = "admin@example.com"
}

if ([string]::IsNullOrWhiteSpace($AdminPassword)) {
    $AdminPassword = "passw0rd!"
}

$Health = Invoke-RestMethod -Method Get "$ApiBaseUrl/api/health"
if ($Health.status -ne "UP") {
    throw "API health is not UP. Current status: $($Health.status)"
}

$LoginBody = @{
    email = $AdminEmail
    password = $AdminPassword
} | ConvertTo-Json

$Login = Invoke-RestMethod -Method Post "$ApiBaseUrl/api/auth/login" `
    -ContentType "application/json" `
    -Body $LoginBody

if ([string]::IsNullOrWhiteSpace($Login.accessToken)) {
    throw "Admin login succeeded but accessToken was not returned."
}

$BackfillBody = @{
    limit = $Limit
} | ConvertTo-Json

$Result = Invoke-RestMethod -Method Post "$ApiBaseUrl/api/admin/rag-embeddings/backfill" `
    -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $($Login.accessToken)" } `
    -Body $BackfillBody

$Result | ConvertTo-Json -Depth 8
