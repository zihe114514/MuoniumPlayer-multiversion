param(
    [string]$Jdk = 'D:/JDK17',
    [string]$MusicRoot = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))),
    [switch]$CompileOnly,
    [ValidateSet('Neutral', 'Native', 'All')]
    [string]$Suite = 'Neutral'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/probe-dependencies.ps1')
$deps = Get-ProbeDependencies -MusicRoot $MusicRoot
$javac = Join-Path $Jdk 'bin/javac.exe'
$java = Join-Path $Jdk 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac)) { throw "Missing Java compiler: $javac" }
$output = Join-Path $PSScriptRoot 'build/core-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$source = Join-Path $MusicRoot 'muonium-common/src/main/java/com/muoniumplayer/configskija'
$files = @()
foreach ($directory in @('model', 'ui', 'host/keys', 'host/sound', 'host/notifications', 'host/migration')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $source $directory) -Filter '*.java' -Recurse | Where-Object Name -ne 'SkijaPainter.java' | ForEach-Object FullName
}
$files += Join-Path $source 'host/HudExtensions.java'
# Select by dependency capability, so adding a native test does not feed it to Gson-only javac.
# Raster/reference/host-library probes retain their dedicated entry points.
$candidates = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java/com/muoniumplayer/configskija') -Filter '*.java' -Recurse | Where-Object { $_.Name -ne 'PreviewMain.java' -and $_.Name -notlike '*ReferenceTest.java' -and $_.Name -notlike '*Raster*' -and $_.Name -ne 'HostLibraryTest.java' -and $_.Name -ne 'OldMusicLoaderTest.java' })
$nativeImports = '(?m)^\s*import\s+(?:static\s+)?(?:io\.github\.humbleui\.(?:skija|types)\.|com\.muoniumplayer\.(?:configskija\.host\.text\.|configskija\.ui\.SkijaPainter\b|render\.skija\.|core\.rendering\.font\.))'
$nativeTests = @($candidates | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -match $nativeImports })
$nativePaths = @{}; foreach ($test in $nativeTests) { $nativePaths[$test.FullName] = $true }
$tests = @($candidates | Where-Object { !$nativePaths.ContainsKey($_.FullName) })
Write-Output ("Probe selection: {0}; neutral sources={1}, native sources={2}" -f $Suite, $tests.Count, $nativeTests.Count)
foreach ($test in $nativeTests) { Write-Output ("Native dependency: " + $test.Name) }
function Invoke-NativeProbeSuite {
    $textDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'src/test/java/com/muoniumplayer/configskija/text')) + [IO.Path]::DirectorySeparatorChar
    $unrouted = @($nativeTests | Where-Object { !$_.FullName.StartsWith($textDirectory, [StringComparison]::OrdinalIgnoreCase) })
    if ($unrouted.Count -gt 0) { throw ('Native tests require a dedicated classpath/runner: ' + (($unrouted | ForEach-Object FullName) -join ', ')) }
    Write-Output 'Native text suite: using text/run.ps1 with the actual cached host classpath and matching Skija native.'
    & (Join-Path $textDirectory 'run.ps1') -Jdk $Jdk -CompileOnly:$CompileOnly
    if ($LASTEXITCODE -ne 0) { throw 'Native text probe suite failed' }
}
if ($Suite -eq 'Native') { Invoke-NativeProbeSuite; return }
if ($Suite -eq 'Neutral' -and $nativeTests.Count -gt 0) {
    Write-Output 'Native text suite not selected. Run verify.ps1 -Suite Native -Jdk <host-capable JDK>, or -Suite All. Raster fixtures run through preview.ps1.'
}
$files += $tests | ForEach-Object FullName
# An argument file avoids Windows command line limits and preserves paths with spaces.
$arguments = @('--release', '17', '-encoding', 'UTF-8', '-cp', ('"' + $deps['gson'].Replace('\','/') + '"'), '-d', ('"' + $output.Replace('\','/') + '"'))
$arguments += $files | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
$argumentFile = Join-Path $output 'sources.args'
[IO.File]::WriteAllLines($argumentFile, $arguments, [Text.UTF8Encoding]::new($false))
& $javac '-J-Duser.language=en' ('@' + $argumentFile)
if ($LASTEXITCODE -ne 0) { throw 'Shared source Java 17 probe compilation failed' }
if ($CompileOnly) { Write-Output "Shared Java 17 probe classes: $output (no MOD/JAR generated)"; if ($Suite -eq 'All') { Invoke-NativeProbeSuite }; return }
foreach ($test in $tests) {
    $content = Get-Content -LiteralPath $test.FullName -Raw
    if (($test.BaseName -like '*Test' -or $test.BaseName -like '*Harness') -and $content -match 'public\s+static\s+void\s+main\s*\(') {
        $package = [regex]::Match($content, 'package\s+([\w.]+)\s*;').Groups[1].Value
        $class = $package + '.' + $test.BaseName
        $testArgs = @()
        if ($test.BaseName -in @('MigrationParityTest','MigrationUnitsTest')) { $testArgs = @((Join-Path $PSScriptRoot 'build/migration-fixtures')) }
        if ($test.BaseName -eq 'SoundResourcesTest') { $testArgs = @((Join-Path $MusicRoot 'muonium-common')) }
        & $java '-Duser.language=en' -ea -cp ($output + ';' + $deps['gson']) $class @testArgs
        if ($LASTEXITCODE -ne 0) { throw "Behavioral probe failed: $class" }
    }
}

if ($Suite -eq 'All') { Invoke-NativeProbeSuite }
