param(
    [string]$MusicRoot = (Split-Path -Parent $PSScriptRoot | Split-Path -Parent),
    [ValidateSet('1.20.1 recode','1.21.1','1.21.10','1.21.11 recode','26.1.2','26.2','26.3')][string]$Version = '26.3',
    [ValidateSet('fabric','neoforge','forge')][string]$Loader = 'fabric',
    [switch]$Production
)
$ErrorActionPreference='Stop'
$project = Split-Path -Parent $PSScriptRoot
$mc = $Version.Replace(' recode','')
$actual = if ($Version -eq '1.20.1 recode' -and $Loader -eq 'neoforge') {'forge'} else {$Loader}
$suffix = if ($Production) {'0.1.0.jar'} else {'0.1.0-dev.jar'}
$directory = if ($Production) {'dist'} else {'build/devdist'}
$source = Join-Path $project ($directory + '/MuoniumConfig-Skija-' + $mc + '-' + $actual + '-' + $suffix)
if (!(Test-Path -LiteralPath $source)) {throw "Build the exact acceptance package first: $source"}
$mods = [IO.Path]::GetFullPath((Join-Path $MusicRoot ($Version + '/' + $actual + '/run/mods')))
$expected = [IO.Path]::GetFullPath((Join-Path $MusicRoot $Version)) + [IO.Path]::DirectorySeparatorChar
if (!$mods.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase)) {throw 'Unexpected run/mods path'}
New-Item -ItemType Directory -Path $mods -Force | Out-Null
$destination = Join-Path $mods 'MuoniumConfig-Skija-acceptance.jar'
Copy-Item -LiteralPath $source -Destination $destination -Force
Get-FileHash -LiteralPath $destination -Algorithm SHA256 | Select-Object Path,Hash
