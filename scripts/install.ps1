<#!
.SYNOPSIS
Installs YuForge for the current Windows user and exposes the `yuforge` command.

.PARAMETER JarPath
Local shaded jar path. Defaults to the Maven package output.

.PARAMETER JarUrl
HTTPS URL of a YuForge Release jar. Takes precedence over JarPath.
#>
param(
    [string]$JarPath = (Join-Path $PSScriptRoot '..\target\yuforge-1.0-SNAPSHOT.jar'),
    [string]$JarUrl = '__YUFORGE_RELEASE_JAR_URL__',
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'YuForge'),
    [switch]$NoPathUpdate
)

$ErrorActionPreference = 'Stop'
$installRoot = $InstallRoot
$binDir = Join-Path $installRoot 'bin'
$targetJar = Join-Path $installRoot 'yuforge.jar'
$launcher = Join-Path $binDir 'yuforge.cmd'

# Source-tree installer keeps this placeholder and installs a local Maven jar by default.
# The release workflow replaces it with the immutable release asset URL.
if ($JarUrl -eq '__YUFORGE_RELEASE_JAR_URL__') {
    $JarUrl = ''
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java 17+ is required. Install a JDK/JRE first, then run this installer again.'
}

New-Item -ItemType Directory -Force -Path $installRoot, $binDir | Out-Null
if ($JarUrl) {
    Write-Host "Downloading YuForge from $JarUrl"
    Invoke-WebRequest -Uri $JarUrl -OutFile $targetJar
} else {
    $resolvedJar = Resolve-Path $JarPath -ErrorAction Stop
    Copy-Item -LiteralPath $resolvedJar -Destination $targetJar -Force
}

@"
@echo off
java -jar "%~dp0..\yuforge.jar" %*
"@ | Set-Content -LiteralPath $launcher -Encoding ascii

if (-not $NoPathUpdate) {
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $pathEntries = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($pathEntries -notcontains $binDir) {
        [Environment]::SetEnvironmentVariable('Path', (($pathEntries + $binDir) -join ';'), 'User')
    }
}

Write-Host "YuForge installed to $installRoot"
if ($NoPathUpdate) {
    Write-Host "PATH was not updated. Run: $launcher"
} else {
    Write-Host 'Open a new terminal, then run: yuforge'
}
