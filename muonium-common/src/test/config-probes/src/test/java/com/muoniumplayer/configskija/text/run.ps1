param(
    [string]$Jdk = 'D:/JDK25',
    [switch]$CompileOnly,
    [string]$HostJar,
    [switch]$UiOnly
)
$ErrorActionPreference = 'Stop'
$textProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../../../'))
$textSource = Join-Path ([IO.Path]::GetFullPath((Join-Path $textProject '../../../..'))) 'muonium-common/src/main/java'
$textOutput = Join-Path $textProject 'build/text-tests'
$textClasspathFile = Join-Path $textProject 'build/host/26.3/fabric/compile-classpath.txt'
if (!(Test-Path -LiteralPath $textClasspathFile)) { throw 'Missing actual 26.3/Fabric host compile classpath' }
$textClasspath = (Get-Content -Raw -LiteralPath $textClasspathFile).Trim()
if ($HostJar) {
    $textActualHost = (Resolve-Path -LiteralPath $HostJar).Path
    $textEntries = $textClasspath.Split(';') | Where-Object { $_ -and $_ -notmatch '[/\\]build[/\\](?:classes|resources)[/\\]' }
    $textClasspath = (@($textActualHost) + $textEntries) -join ';'
    $textOutput = Join-Path $textProject 'build/text-tests-installed-host'
}
$textFiles = @(Get-ChildItem -LiteralPath (Join-Path $textSource 'com/muoniumplayer/configskija/host/text') -Filter '*.java' | ForEach-Object FullName)
# Explicitly compile helpers reached reflectively by the centering contract probe.
$textFiles += @('ui/Painter.java','ui/SkijaPainter.java','ui/settings/SettingsDraw.java',
    'ui/settings/SettingsControls.java','ui/pages/LibraryPaint.java','ui/pages/AppearanceControls.java',
    'ui/hud/HudWidgets.java','ui/hud/HudChromeContext.java') | ForEach-Object {
    Join-Path $textSource ('com/muoniumplayer/configskija/' + $_)
}
$textFiles += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName
New-Item -ItemType Directory -Path $textOutput -Force | Out-Null
& (Join-Path $Jdk 'bin/javac.exe') --release 17 -encoding UTF-8 -proc:none -classpath $textClasspath -sourcepath $textSource -d $textOutput @textFiles
if ($LASTEXITCODE -ne 0) { throw 'Text adapter Java 17 target compilation failed' }
$textResources = Join-Path ([IO.Path]::GetFullPath((Join-Path $textProject '../../../..'))) 'muonium-common/src/main/resources'
$textRunClasspath = $textOutput + ';' + $textResources + ';' + $textClasspath
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $textRunClasspath com.muoniumplayer.configskija.text.HostTextContractTest (Join-Path ([IO.Path]::GetFullPath((Join-Path $textProject '../../../..'))) 'muonium-common') $textOutput
if ($LASTEXITCODE -ne 0) { throw 'Text adapter contract/resource/ownership guards failed' }
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $textRunClasspath com.muoniumplayer.configskija.text.TextCenteringTest
if ($LASTEXITCODE -ne 0) { throw 'Text centering contract/helper tests failed' }
if ($CompileOnly) { return }
$textShared = $textClasspath.Split(';') | Where-Object { [IO.Path]::GetFileName($_) -like 'skija-shared-*.jar' } | Select-Object -First 1
if (!$textShared) { throw 'No actual host Skija version in the classpath' }
$textVersion = [IO.Path]::GetFileName($textShared).Replace('skija-shared-','').Replace('.jar','')
$textGroup = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $textShared)))
$textNativeRoot = Join-Path $textGroup "skija-windows-x64/$textVersion"
$textNative = Get-ChildItem -LiteralPath $textNativeRoot -Filter '*.jar' -Recurse | Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } | Select-Object -First 1
if (!$textNative) { throw "Matching host native runtime is not cached: $textVersion" }
$textRunClasspath += ';' + $textNative.FullName
& (Join-Path $Jdk 'bin/java.exe') --enable-native-access=ALL-UNNAMED -ea -cp $textRunClasspath com.muoniumplayer.configskija.text.HostTextRendererTest @($(if ($UiOnly) { "--ui-only" }))
if ($LASTEXITCODE -ne 0) { throw 'CPU text draw/measure/lifecycle tests failed' }
