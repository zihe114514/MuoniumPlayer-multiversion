param([string]$Jdk = 'D:/JDK17', [string]$InspectRun = '')
$ErrorActionPreference = 'Stop'
$migrationRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../../../..')).Path
if (!(Test-Path -LiteralPath (Join-Path $migrationRoot 'AGENTS.md'))) { throw "Invalid migration workspace: $migrationRoot" }
$migrationOutput = Join-Path $migrationRoot 'build/migration-tests'
New-Item -ItemType Directory -Path $migrationOutput -Force | Out-Null
$migrationSources = @()
$migrationSources += Get-ChildItem -LiteralPath (Join-Path ([IO.Path]::GetFullPath((Join-Path $migrationRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/model') -Recurse -Filter '*.java' | ForEach-Object FullName
$migrationSources += Get-ChildItem -LiteralPath (Join-Path ([IO.Path]::GetFullPath((Join-Path $migrationRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/ui') -Recurse -Filter '*.java' | Where-Object Name -ne 'SkijaPainter.java' | ForEach-Object FullName
$migrationSources += Join-Path ([IO.Path]::GetFullPath((Join-Path $migrationRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/host/HudExtensions.java'
$migrationSources += Get-ChildItem -LiteralPath (Join-Path ([IO.Path]::GetFullPath((Join-Path $migrationRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/host/migration') -Filter '*.java' | ForEach-Object FullName
$migrationSources += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName
. (Join-Path $migrationRoot 'tools/probe-dependencies.ps1')
$migrationDeps = Get-ProbeDependencies -MusicRoot ([IO.Path]::GetFullPath((Join-Path $migrationRoot '../../../..')))
& (Join-Path $Jdk 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $migrationDeps['gson'] -d $migrationOutput @migrationSources
if ($LASTEXITCODE -ne 0) { throw 'Migration Java 17 integration compilation failed' }
$migrationLog = @(& (Join-Path $Jdk 'bin/java.exe') -ea -cp ($migrationOutput + ';' + $migrationDeps['gson']) com.muoniumplayer.configskija.migration.MigrationParityTest (Join-Path $migrationOutput 'fixtures'))
$migrationExit = $LASTEXITCODE
$migrationLog | ForEach-Object { Write-Output $_ }
$migrationLog | Set-Content -LiteralPath (Join-Path $migrationOutput 'results.txt') -Encoding utf8
if ($migrationExit -ne 0) { throw 'Migration behavioral checks failed' }
$migrationUnitsLog = @(& (Join-Path $Jdk 'bin/java.exe') -ea -cp ($migrationOutput + ';' + $migrationDeps['gson']) com.muoniumplayer.configskija.migration.MigrationUnitsTest (Join-Path $migrationOutput 'unit-fixtures'))
$migrationUnitsExit = $LASTEXITCODE
$migrationUnitsLog | ForEach-Object { Write-Output $_ }
$migrationUnitsLog | Set-Content -LiteralPath (Join-Path $migrationOutput 'units-results.txt') -Encoding utf8
if ($migrationUnitsExit -ne 0) { throw 'Migration GUI/layout unit matrix failed' }
$migrationFingerprint = @(Get-ChildItem -LiteralPath (Join-Path ([IO.Path]::GetFullPath((Join-Path $migrationRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/host/migration') -Filter '*.java' | ForEach-Object { [ordered]@{path = 'src/main/java/com/muoniumplayer/configskija/host/migration/' + $_.Name;sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
$migrationBaseline=Get-Content -LiteralPath (Join-Path $migrationRoot 'reference/source-manifest.json') -Raw | ConvertFrom-Json
[ordered]@{schemaVersion=1;kind='neutral-behavior';result='pass';sourceHead=$migrationBaseline.source.head;sourceTreeSha256=$migrationBaseline.sourceTreeSha256;summary=$migrationLog[0]+'; '+$migrationUnitsLog[0];implementation=$migrationFingerprint;groups=@(@($migrationLog)+@($migrationUnitsLog)|Where-Object {$_ -like 'PASS *'}|ForEach-Object {$_.Substring(5)});human='pending; no live client application or visual parity claimed';legacyFiles='never written, moved or deleted; fixture-only hashes are test assertions'} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $migrationOutput 'behavior-results.json') -Encoding utf8
if ($InspectRun) {
    & (Join-Path $Jdk 'bin/java.exe') -cp ($migrationOutput + ';' + $migrationDeps['gson']) com.muoniumplayer.configskija.migration.MigrationInspectMain $InspectRun
    if ($LASTEXITCODE -ne 0) { throw 'Read-only migration inspection failed' }
}
