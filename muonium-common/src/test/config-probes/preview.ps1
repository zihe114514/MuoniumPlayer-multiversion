param([string]$Jdk = 'D:/JDK17', [string]$MusicRoot = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))))
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify.ps1') -Jdk $Jdk -MusicRoot $MusicRoot
. (Join-Path $PSScriptRoot 'tools/probe-dependencies.ps1')
$deps = Get-ProbeDependencies -MusicRoot $MusicRoot
$output = Join-Path $PSScriptRoot 'build/core-tests'
$resources = Join-Path $MusicRoot 'muonium-common/src/main/resources'
$cp = @($output, $resources, $deps['skija-shared'], $deps['types'], $deps['skija-windows-x64']) -join ';'
$extra = @((Join-Path $MusicRoot 'muonium-common/src/main/java/com/muoniumplayer/configskija/ui/SkijaPainter.java'), (Join-Path $PSScriptRoot 'src/test/java/com/muoniumplayer/configskija/PreviewMain.java'), (Join-Path $PSScriptRoot 'src/test/java/com/muoniumplayer/configskija/hud/HudPanelRasterPreview.java'))
& (Join-Path $Jdk 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $output @extra
if ($LASTEXITCODE -ne 0) { throw 'Raster probe compilation failed' }
& (Join-Path $Jdk 'bin/java.exe') '-Djava.awt.headless=true' -ea -cp $cp com.muoniumplayer.configskija.PreviewMain $MusicRoot (Join-Path $PSScriptRoot 'build/previews')
if ($LASTEXITCODE -ne 0) { throw 'Page raster rendering failed' }
& (Join-Path $Jdk 'bin/java.exe') '-Djava.awt.headless=true' -ea -cp $cp com.muoniumplayer.configskija.ui.hud.HudPanelRasterPreview $MusicRoot (Join-Path $PSScriptRoot 'build/hud-previews')
if ($LASTEXITCODE -ne 0) { throw 'HUD panel raster rendering failed' }
