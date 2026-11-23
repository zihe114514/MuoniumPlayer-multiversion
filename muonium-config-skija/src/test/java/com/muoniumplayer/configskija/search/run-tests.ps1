param([string]$Jdk = 'D:/JDK17')
$ErrorActionPreference = 'Stop'
$searchRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../../../..')).Path
if (!(Test-Path -LiteralPath (Join-Path $searchRoot 'AGENTS.md'))) { throw "Cannot resolve search workspace: $searchRoot" }
$searchCompiler = Join-Path $Jdk 'bin/javac.exe'
$searchRuntime = Join-Path $Jdk 'bin/java.exe'
if (!(Test-Path -LiteralPath $searchCompiler)) { throw "Missing Java 17 compiler: $searchCompiler" }
$searchOutput = Join-Path $searchRoot 'build/search-tests'
New-Item -ItemType Directory -Path $searchOutput -Force | Out-Null
$searchSources = @()
$searchSources += Get-ChildItem -LiteralPath (Join-Path $searchRoot 'src/main/java/com/muoniumplayer/configskija/model') -Recurse -Filter '*.java' | ForEach-Object FullName
$searchSources += Get-ChildItem -LiteralPath (Join-Path $searchRoot 'src/main/java/com/muoniumplayer/configskija/ui') -Recurse -Filter '*.java' | Where-Object Name -ne 'SkijaPainter.java' | ForEach-Object FullName
$searchSources += Join-Path $searchRoot 'src/main/java/com/muoniumplayer/configskija/host/HudExtensions.java'
$searchSources += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName
& $searchCompiler --release 17 -encoding UTF-8 -d $searchOutput @searchSources
if ($LASTEXITCODE -ne 0) { throw 'Search Java 17 integration compilation failed' }
$searchLog = @(& $searchRuntime -ea -cp $searchOutput com.muoniumplayer.configskija.search.SearchParityTest)
$searchExit = $LASTEXITCODE
$searchLog | ForEach-Object { Write-Output $_ }
$searchLog | Set-Content -LiteralPath (Join-Path $searchOutput 'results.txt') -Encoding utf8
if ($searchExit -ne 0) { throw 'Search behavioral checks failed' }
$searchGlobalLog = @(& $searchRuntime -ea -cp $searchOutput com.muoniumplayer.configskija.search.GlobalResultsRendererTest)
$searchGlobalExit = $LASTEXITCODE
$searchGlobalLog | ForEach-Object { Write-Output $_ }
$searchGlobalLog | Set-Content -LiteralPath (Join-Path $searchOutput 'global-results.txt') -Encoding utf8
if ($searchGlobalExit -ne 0) { throw 'Global real card/setting renderer checks failed' }
& $searchRuntime -ea -cp $searchOutput com.muoniumplayer.configskija.search.HistoryBoundaryAuditTest
if ($LASTEXITCODE -ne 0) { throw 'History boundary audit reproduction changed; review the report against the new integration' }
$searchBaseline = Get-Content -LiteralPath (Join-Path $searchRoot 'reference/source-manifest.json') -Raw | ConvertFrom-Json
$searchOwned = @('src/main/java/com/muoniumplayer/configskija/model/SearchIndex.java', 'src/main/java/com/muoniumplayer/configskija/model/SearchDocuments.java')
$searchOwned += Get-ChildItem -LiteralPath (Join-Path $searchRoot 'src/main/java/com/muoniumplayer/configskija/ui/search') -Filter '*.java' | ForEach-Object { 'src/main/java/com/muoniumplayer/configskija/ui/search/' + $_.Name }
$searchFingerprints = @($searchOwned | ForEach-Object { [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $searchRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant() } })
$searchShared = @('src/main/java/com/muoniumplayer/configskija/ui/settings/EntryRows.java', 'src/main/java/com/muoniumplayer/configskija/ui/settings/SettingsPage.java', 'src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryMods.java')
$searchDependencies = @($searchShared | ForEach-Object { [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $searchRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant() } })
$searchSummary = [ordered]@{
    schemaVersion = 1
    kind = 'neutral-behavior'
    sourceHead = $searchBaseline.source.head
    sourceTreeSha256 = $searchBaseline.sourceTreeSha256
    implementation = $searchFingerprints
    dependencies = $searchDependencies
    result = 'pass'
    command = 'javac --release 17; java -ea SearchParityTest; java -ea GlobalResultsRendererTest'
    summary = $searchLog[0] + '; ' + $searchGlobalLog[0]
    groups = @(@($searchLog) + @($searchGlobalLog) | Where-Object { $_ -like 'PASS *' } | ForEach-Object { $_.Substring(5) })
    runtimeTargets = 'not-run; this harness does not establish fourteen-loader runtime or visual parity'
    historyAudit = 'three historical unsafe-call examples reproduced; current host snapshot/scope/restore fixes reviewed separately'
    game = 'human-acceptance-pending'
    visual = 'human-acceptance-pending'
}
$searchSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $searchOutput 'behavior-results.json') -Encoding utf8
