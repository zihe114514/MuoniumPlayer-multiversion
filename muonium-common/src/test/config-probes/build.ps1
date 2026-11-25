param([string]$Jdk = 'D:/JDK17', [string]$MusicRoot = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))))
$ErrorActionPreference = 'Stop'
# Retained entry point: compile neutral probes from the integrated source tree only.
& (Join-Path $PSScriptRoot 'verify.ps1') -Jdk $Jdk -MusicRoot $MusicRoot -CompileOnly
