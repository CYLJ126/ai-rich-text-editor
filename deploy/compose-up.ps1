param(
  [switch]$BuildLocal,
  [switch]$External,
  [switch]$Observability,
  [switch]$Https,
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$ComposeArgs
)

$ErrorActionPreference = 'Stop'
$scriptRoot = $PSScriptRoot
Set-Location $scriptRoot

if (-not (Test-Path -LiteralPath '.env') -and (Test-Path -LiteralPath '.env.example')) {
  Copy-Item -LiteralPath '.env.example' -Destination '.env'
  Write-Host 'Created deploy/.env from .env.example. Change the default passwords before exposing the service publicly.'
}

$baseComposeFile = if ($External) { 'docker-compose.external.yml' } else { 'docker-compose.yml' }
$composeFiles = @('-f', $baseComposeFile)
if ($BuildLocal) {
  $buildComposeFile = if ($External) { 'docker-compose.external.build.yml' } else { 'docker-compose.build.yml' }
  $composeFiles += @('-f', $buildComposeFile)
}
if ($Https) {
  $composeFiles += @('-f', 'docker-compose.https.yml')
}

$profileArgs = @()
if ($Observability) {
  if ($External) {
    throw 'Observability profile is only available with bundled Elasticsearch.'
  }
  $profileArgs = @('--profile', 'observability')
}

& docker compose @composeFiles @profileArgs config --quiet
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

$upArgs = @('up', '-d', '--wait', '--wait-timeout', '900')
if ($BuildLocal) {
  $upArgs += @('--build', '--pull', 'never')
}

& docker compose @composeFiles @profileArgs @upArgs @ComposeArgs
if ($LASTEXITCODE -ne 0) {
  $exitCode = $LASTEXITCODE
  & docker compose @composeFiles @profileArgs ps
  exit $exitCode
}

$frontendBinding = (& docker compose @composeFiles @profileArgs port frontend 8080 | Select-Object -First 1)
$port = if ($frontendBinding) { ($frontendBinding -split ':')[-1] } else { '8000' }
Write-Host ''
Write-Host "AIRichTextEditor is ready: http://localhost:$port"
if ($Https) {
  $httpsBinding = (& docker compose @composeFiles @profileArgs port frontend 8443 | Select-Object -First 1)
  Write-Host "HTTPS is ready: https://localhost:$(($httpsBinding -split ':')[-1])"
}
if ($Observability) {
  $kibanaBinding = (& docker compose @composeFiles @profileArgs port kibana 5601 | Select-Object -First 1)
  $kibanaPort = if ($kibanaBinding) { ($kibanaBinding -split ':')[-1] } else { '5601' }
  Write-Host "Kibana is ready: http://localhost:$kibanaPort"
}
