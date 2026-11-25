param([string]$Jdk = 'D:/JDK17', [switch]$Raster)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../../..'))
if (!(Test-Path -LiteralPath (Join-Path ([IO.Path]::GetFullPath((Join-Path $taskRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/ui/PageContext.java'))) { throw 'Settings workspace root did not resolve correctly.' }
$taskOutput = Join-Path $taskRoot 'build/settings-tests'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskMain = Join-Path ([IO.Path]::GetFullPath((Join-Path $taskRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija'
$taskSources = @()
$taskSources += Get-ChildItem -LiteralPath (Join-Path $taskMain 'model') -Filter '*.java' | ForEach-Object { $_.FullName }
$taskSources += Get-ChildItem -LiteralPath (Join-Path $taskMain 'ui') -Recurse -Filter '*.java' | Where-Object { $_.Name -ne 'SkijaPainter.java' } | ForEach-Object { $_.FullName }
# ConfigView now references neutral HUD and page contracts; compile them together without MC/native code.
$taskSources += Join-Path $taskMain 'host/HudExtensions.java'
$taskSources += Join-Path $PSScriptRoot 'SettingsHarness.java'
$taskSources += Join-Path $PSScriptRoot 'TextInputHarness.java'
$taskSources += Join-Path $PSScriptRoot 'EntryRowsHarness.java'
$taskJavac = Join-Path $Jdk 'bin/javac.exe'
$taskJava = Join-Path $Jdk 'bin/java.exe'
& $taskJavac '-J-Duser.language=en' --release 17 -encoding UTF-8 -d $taskOutput @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Settings Java 17 compilation failed.' }
& $taskJava -ea -cp $taskOutput com.muoniumplayer.configskija.settings.SettingsHarness
if ($LASTEXITCODE -ne 0) { throw 'Settings behavioral harness failed.' }
& $taskJava -ea -cp $taskOutput com.muoniumplayer.configskija.settings.TextInputHarness
if ($LASTEXITCODE -ne 0) { throw 'Text input event harness failed.' }
& $taskJava -ea -cp $taskOutput com.muoniumplayer.configskija.settings.EntryRowsHarness
if ($LASTEXITCODE -ne 0) { throw 'Global settings row adapter harness failed.' }
if (!$Raster) { return }
$taskClasspathFile = Join-Path $taskRoot 'build/host/26.3/fabric/compile-classpath.txt'
if (!(Test-Path -LiteralPath $taskClasspathFile)) { throw 'Raster verification requires the existing host classpath snapshot; this script never starts Gradle.' }
$taskEntries = (Get-Content -LiteralPath $taskClasspathFile -Raw).Split(';')
$taskSkija = $taskEntries | Where-Object { [IO.Path]::GetFileName($_) -like 'skija-shared-*.jar' } | Select-Object -First 1
$taskTypes = $taskEntries | Where-Object { [IO.Path]::GetFileName($_) -like 'types-*.jar' } | Select-Object -First 1
if (!$taskSkija -or !$taskTypes) { throw 'Exact host Skija dependencies are missing from the classpath snapshot.' }
$taskVersion = [IO.Path]::GetFileNameWithoutExtension($taskSkija).Substring('skija-shared-'.Length)
$taskGroup = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $taskSkija)))
$taskNativeFolder = Join-Path $taskGroup ('skija-windows-x64/' + $taskVersion)
$taskNative = Get-ChildItem -LiteralPath $taskNativeFolder -Recurse -Filter ('skija-windows-x64-' + $taskVersion + '.jar') | Select-Object -First 1
if (!$taskNative) { throw 'Matching host Skija native runtime is not cached.' }
$taskClasspath = @($taskOutput,(Join-Path ([IO.Path]::GetFullPath((Join-Path $taskRoot '../../../..'))) 'muonium-common/src/main/resources'),$taskSkija,$taskTypes,$taskNative.FullName) -join ';'
& $taskJavac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $taskClasspath -d $taskOutput (Join-Path $taskMain 'ui/SkijaPainter.java') (Join-Path $PSScriptRoot 'raster/SettingsRaster.java')
if ($LASTEXITCODE -ne 0) { throw 'Settings Skija raster harness compilation failed.' }
& $taskJava -ea -cp $taskClasspath com.muoniumplayer.configskija.settings.SettingsRaster ([IO.Path]::GetFullPath((Join-Path $taskRoot '../../../..'))) (Join-Path $taskRoot 'build/settings-previews')
if ($LASTEXITCODE -ne 0) { throw 'Settings Skija raster rendering failed.' }
