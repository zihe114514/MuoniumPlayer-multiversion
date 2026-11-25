param([string]$Jdk='D:/JDK17',[switch]$ComposeReference,[string]$ReferenceRoot='E:/oneconfig lite/MuoniumConfig-src')
$ErrorActionPreference='Stop'
$hudRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../../../'))
$hudSource=Join-Path ([IO.Path]::GetFullPath((Join-Path $hudRoot '../../../..'))) 'muonium-common/src/main/java'
$hudOutput=Join-Path $hudRoot 'build/hud-tests'
New-Item -ItemType Directory -Path $hudOutput -Force | Out-Null
$hudFiles=@(Get-ChildItem -LiteralPath (Join-Path $hudSource 'com/muoniumplayer/configskija/ui/hud') -Filter '*.java' | ForEach-Object FullName)
$hudFiles+=Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | Where-Object Name -NotLike '*Raster*' | ForEach-Object FullName
& (Join-Path $Jdk 'bin/javac.exe') --release 17 -encoding UTF-8 -sourcepath $hudSource -d $hudOutput @hudFiles
if($LASTEXITCODE -ne 0){throw 'HUD local Java 17 compilation failed'}
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $hudOutput com.muoniumplayer.configskija.ui.hud.HudParityTest
if($LASTEXITCODE -ne 0){throw 'HUD behavior tests failed'}
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $hudOutput com.muoniumplayer.configskija.ui.hud.HudUiDetailsTest
if($LASTEXITCODE -ne 0){throw 'HUD UI detail source-parity tests failed'}
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $hudOutput com.muoniumplayer.configskija.ui.hud.HudBindingsTest
if($LASTEXITCODE -ne 0){throw 'HUD rebinding tests failed'}
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $hudOutput com.muoniumplayer.configskija.ui.hud.HudUnitsTest
if($LASTEXITCODE -ne 0){throw 'HUD physical-unit tests failed'}
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $hudOutput com.muoniumplayer.configskija.ui.hud.HudNumberLayoutTest
if($LASTEXITCODE -ne 0){throw 'HUD responsive number layout/edit tests failed'}
if($ComposeReference){
    $hudCache=if($env:GRADLE_USER_HOME){Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1'}else{Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'}
    $hudVersions=Get-Content -Raw -LiteralPath (Join-Path $ReferenceRoot 'gradle/libs.versions.toml')
    $hudCompose=[regex]::Match($hudVersions,'(?m)^compose = "([^"\r\n]+)"').Groups[1].Value
    $hudKotlin=[regex]::Match($hudVersions,'(?m)^kotlin = "([^"\r\n]+)"').Groups[1].Value
    if(!$hudCompose -or !$hudKotlin){throw 'Original resolved versions missing'}
    $hudCoordinates=@("org.jetbrains.compose.animation/animation-core-desktop/$hudCompose","org.jetbrains.kotlin/kotlin-stdlib/$hudKotlin")
    $hudJars=@()
    foreach($coordinate in $hudCoordinates){
        $hudDirectory=Join-Path $hudCache $coordinate
        if(!(Test-Path -LiteralPath $hudDirectory)){throw "Missing exact reference dependency: $coordinate"}
        $hudJar=Get-ChildItem -LiteralPath $hudDirectory -Recurse -Filter '*.jar' | Where-Object {$_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*'} | Select-Object -First 1
        if(!$hudJar){throw "Missing reference dependency: $coordinate"};$hudJars+=$hudJar.FullName
    }
    & (Join-Path $Jdk 'bin/java.exe') -ea -cp ($hudOutput+[IO.Path]::PathSeparator+($hudJars -join [IO.Path]::PathSeparator)) com.muoniumplayer.configskija.ui.hud.HudMotionReferenceTest
    if($LASTEXITCODE -ne 0){throw 'HUD resolved Compose reference mismatch'}
}
