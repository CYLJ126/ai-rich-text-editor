param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$ComposeArgs
)

$ErrorActionPreference = 'Stop'
$scriptRoot = $PSScriptRoot
Set-Location $scriptRoot

function Get-EnvFileValue([string]$Key) {
  $envPath = Join-Path $scriptRoot '.env'
  if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    return ''
  }

  foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $envPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
      continue
    }

    if ($trimmed -match "^$([regex]::Escape($Key))=(.*)$") {
      $value = $Matches[1].Trim()
      if (
        ($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))
      ) {
        $value = $value.Substring(1, $value.Length - 2)
      }
      return $value
    }
  }

  return ''
}

function Get-ConfigValue([string]$Key) {
  $value = [Environment]::GetEnvironmentVariable($Key)
  if (-not [string]::IsNullOrWhiteSpace($value)) {
    return $value
  }

  return Get-EnvFileValue $Key
}

function Prepare-Artifact(
  [string]$Name,
  [string]$Source,
  [string]$TargetDirectory,
  [string]$Extension,
  [string]$BuildVariable
) {
  $uuid = [guid]::NewGuid().ToString()
  $targetFile = "$Name-$uuid.$Extension"
  $targetDirectoryPath = Join-Path $scriptRoot $TargetDirectory
  $targetPath = Join-Path $targetDirectoryPath $targetFile

  New-Item -ItemType Directory -Force -Path $targetDirectoryPath | Out-Null
  Get-ChildItem -Force -Path $targetDirectoryPath | Remove-Item -Force -Recurse

  if ($Source -match '^https?://') {
    Write-Host "Downloading $Name artifact..."
    Invoke-WebRequest -Uri $Source -OutFile $targetPath
  } else {
    $localSource = if ([IO.Path]::IsPathRooted($Source)) {
      $Source
    } else {
      Join-Path $scriptRoot $Source
    }

    if (-not (Test-Path -LiteralPath $localSource -PathType Leaf)) {
      throw "$Name artifact not found: $Source"
    }

    Write-Host "Copying local $Name artifact..."
    Copy-Item -LiteralPath $localSource -Destination $targetPath
  }

  Set-Item -Path "Env:$BuildVariable" -Value "artifacts/$targetFile"
  Write-Host "Prepared ${Name}: artifacts/$targetFile"
}

$appSource = Get-ConfigValue 'NIP_APP_JAR_SOURCE'
$frontSource = Get-ConfigValue 'NIP_FRONT_DIST_SOURCE'

if ([string]::IsNullOrWhiteSpace($appSource)) {
  throw 'NIP_APP_JAR_SOURCE is required. Set it to a local jar path or an http(s) URL.'
}

if ([string]::IsNullOrWhiteSpace($frontSource)) {
  throw 'NIP_FRONT_DIST_SOURCE is required. Set it to a local dist.zip path or an http(s) URL.'
}

Prepare-Artifact 'nip-app-boot' $appSource 'backend/artifacts' 'jar' 'NIP_APP_JAR_FILE'
Prepare-Artifact 'dist' $frontSource 'frontend/artifacts' 'zip' 'NIP_FRONT_DIST_FILE'

$composeOptions = [System.Collections.Generic.List[string]]::new()
$serviceArgs = [System.Collections.Generic.List[string]]::new()
for ($index = 0; $index -lt $ComposeArgs.Count; $index++) {
  if ($ComposeArgs[$index] -eq '--profile') {
    if ($index + 1 -ge $ComposeArgs.Count) {
      throw '--profile requires a profile name'
    }
    $composeOptions.Add('--profile')
    $index++
    $composeOptions.Add($ComposeArgs[$index])
  } elseif ($ComposeArgs[$index] -eq '--') {
    for ($index++; $index -lt $ComposeArgs.Count; $index++) {
      $serviceArgs.Add($ComposeArgs[$index])
    }
    break
  } else {
    $serviceArgs.Add($ComposeArgs[$index])
  }
}

& docker compose @composeOptions up -d --build --force-recreate @serviceArgs
