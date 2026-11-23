param([string]$Jdk = 'D:\JDK17')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verify.ps1') -Jdk $Jdk
$MusicRoot = Split-Path -Parent $PSScriptRoot
$classPathFile = Join-Path $PSScriptRoot 'build/host/26.3/fabric/compile-classpath.txt'
if (!(Test-Path -LiteralPath $classPathFile)) { throw 'Run build.ps1 for 26.3 fabric before the raster smoke test.' }
$hostEntries = (Get-Content -LiteralPath $classPathFile -Raw).Split(';')
$shared = $hostEntries | Where-Object { [IO.Path]::GetFileName($_) -like 'skija-shared-*.jar' } | Select-Object -First 1
$types = $hostEntries | Where-Object { [IO.Path]::GetFileName($_) -like 'types-*.jar' } | Select-Object -First 1
if (!$shared -or !$types) { throw 'Exact host Skija dependencies are absent.' }
$groupRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $shared)))
$native = Get-ChildItem -Path (Join-Path $groupRoot 'skija-windows-x64/*/*/skija-windows-x64-*.jar') -File | Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } | Select-Object -First 1
if (!$native) { throw 'The matching host Windows Skija runtime is not cached.' }
$output = Join-Path $PSScriptRoot 'build/core-tests'
$resources = Join-Path $PSScriptRoot 'src/main/resources'
$cp = @($output,$resources,$shared,$types,$native.FullName) -join ';'
$extra = @((Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/ui/SkijaPainter.java'),(Join-Path $PSScriptRoot 'src/test/java/com/muoniumplayer/configskija/PreviewMain.java'))
& (Join-Path $Jdk 'bin/javac.exe') '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $cp -d $output @extra
if ($LASTEXITCODE -ne 0) { throw 'Raster preview compilation failed' }
& (Join-Path $Jdk 'bin/java.exe') -ea -cp $cp com.muoniumplayer.configskija.PreviewMain $MusicRoot (Join-Path $PSScriptRoot 'build/previews')
if ($LASTEXITCODE -ne 0) { throw 'Raster rendering failed' }
