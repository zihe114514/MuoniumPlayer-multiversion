param([string]$Jdk = 'D:\JDK17')
$ErrorActionPreference = 'Stop'
$javac = Join-Path $Jdk 'bin/javac.exe'
$java = Join-Path $Jdk 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac)) { throw "Missing Java 17 compiler: $javac" }
$output = Join-Path $PSScriptRoot 'build/core-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$files = @()
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/model') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/ui') -Filter '*.java' -Recurse | Where-Object { $_.Name -ne 'SkijaPainter.java' } | ForEach-Object { $_.FullName }
$files += Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/host/HudExtensions.java'
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/host/keys') -Filter '*.java' | ForEach-Object { $_.FullName }
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/host/sound') -Filter '*.java' | ForEach-Object { $_.FullName }
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/host/notifications') -Filter '*.java' | ForEach-Object { $_.FullName }
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/muoniumplayer/configskija/host/migration') -Filter '*.java' | ForEach-Object { $_.FullName }
$hostClassPathFile = Join-Path $PSScriptRoot 'build/host/26.3/fabric/compile-classpath.txt'
if (!(Test-Path -LiteralPath $hostClassPathFile)) { throw 'Resolve the 26.3 Fabric compile classpath before running source-backed migration tests.' }
$gson = (Get-Content -LiteralPath $hostClassPathFile -Raw).Split(';') | Where-Object { [IO.Path]::GetFileName($_) -like 'gson-*.jar' } | Select-Object -First 1
if (!$gson) { throw 'The actual host Gson dependency is missing.' }
$tests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java/com/muoniumplayer/configskija') -Filter '*.java' -Recurse | Where-Object { $_.Name -ne 'PreviewMain.java' -and $_.Name -notlike '*ReferenceTest.java' -and $_.Name -notlike '*Raster*' -and $_.Name -notlike 'HostText*Test.java' -and $_.Name -ne 'HostLibraryTest.java' -and $_.Name -ne 'OldMusicLoaderTest.java' })
$files += $tests | ForEach-Object { $_.FullName }
& $javac '-J-Duser.language=en' --release 17 -encoding UTF-8 -cp $gson -d $output @files
if ($LASTEXITCODE -ne 0) { throw 'Core test compilation failed' }
foreach ($test in $tests) {
    $source = Get-Content -LiteralPath $test.FullName -Raw
    if (($test.BaseName -like '*Test' -or $test.BaseName -like '*Harness') -and $source -match 'public\s+static\s+void\s+main\s*\(') {
        $package = [regex]::Match($source, 'package\s+([\w.]+)\s*;').Groups[1].Value
        $class = $package + '.' + $test.BaseName
        $testArgs = @()
        if ($test.BaseName -in @('MigrationParityTest','MigrationUnitsTest')) { $testArgs = @((Join-Path $PSScriptRoot 'build/migration-fixtures')) }
        if ($test.BaseName -eq 'SoundResourcesTest') { $testArgs = @($PSScriptRoot, 'E:\oneconfig lite\MuoniumConfig-src', 'E:\oneconfig lite\OneConfig') }
        & $java '-Duser.language=en' -ea -cp ($output + ';' + $gson) $class @testArgs
        if ($LASTEXITCODE -ne 0) { throw "Behavioral tests failed: $class" }
    }
}
