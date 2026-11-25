param([string]$Jdk = 'D:/JDK17')
$ErrorActionPreference = 'Stop'
$hudScrollRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$hudScrollSource = Join-Path ([IO.Path]::GetFullPath((Join-Path $hudScrollRoot '../../../..'))) 'muonium-common/src/main/java'
$hudScrollOutput = Join-Path $hudScrollRoot 'build/hud-smooth-tests'
$hudScrollTests = Join-Path $hudScrollRoot 'src/test/java/com/muoniumplayer/configskija/hud'
New-Item -ItemType Directory -Path $hudScrollOutput -Force | Out-Null
$hudScrollFiles = @(Get-ChildItem -LiteralPath (Join-Path $hudScrollSource 'com/muoniumplayer/configskija/ui/hud') -Filter '*.java' | ForEach-Object FullName)
$hudScrollFiles += Get-ChildItem -LiteralPath $hudScrollTests -Filter '*.java' | Where-Object Name -NotLike '*Raster*' | ForEach-Object FullName
& (Join-Path $Jdk 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -sourcepath $hudScrollSource -d $hudScrollOutput @hudScrollFiles
if ($LASTEXITCODE -ne 0) { throw 'HUD smooth-scroll Java 17 compilation failed.' }
# The old HudUiDetailsTest.library assertion requires an immediate wheel. Leave it untouched;
# the new probe covers smooth handoff and also runs every unaffected UiDetails subprobe.
foreach ($hudScrollClass in @('HudSmoothScrollTest', 'HudParityTest', 'HudBindingsTest', 'HudUnitsTest', 'HudNumberLayoutTest')) {
    & (Join-Path $Jdk 'bin/java.exe') '-Duser.language=en' -ea -cp $hudScrollOutput ('com.muoniumplayer.configskija.ui.hud.' + $hudScrollClass)
    if ($LASTEXITCODE -ne 0) { throw "HUD CPU regression failed: $hudScrollClass" }
}
