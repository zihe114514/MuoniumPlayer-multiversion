param([string]$Jdk = 'D:/JDK17')
$ErrorActionPreference = 'Stop'
$interactionRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$interactionMusic = [IO.Path]::GetFullPath((Join-Path $interactionRoot '../../../..'))
. (Join-Path $interactionRoot 'tools/probe-dependencies.ps1')
$interactionDeps = Get-ProbeDependencies -MusicRoot $interactionMusic
$interactionSource = Join-Path $interactionMusic 'muonium-common/src/main/java/com/muoniumplayer/configskija'
$interactionOutput = Join-Path $interactionRoot 'build/interaction-tests'
New-Item -ItemType Directory -Path $interactionOutput -Force | Out-Null
$interactionFiles = @()
foreach ($interactionDirectory in @('model', 'ui', 'host/keys', 'host/sound', 'host/notifications', 'host/migration')) {
    $interactionFiles += Get-ChildItem -LiteralPath (Join-Path $interactionSource $interactionDirectory) -Filter '*.java' -Recurse |
        Where-Object Name -ne 'SkijaPainter.java' | ForEach-Object FullName
}
$interactionFiles += Join-Path $interactionSource 'host/HudExtensions.java'
$interactionTests = Join-Path $interactionRoot 'src/test/java/com/muoniumplayer/configskija'
foreach ($interactionTest in @('ui/ScrollMotionTest.java', 'ui/ConfigInteractionTest.java', 'settings/SettingsHarness.java', 'settings/EntryRowsHarness.java', 'settings/TextInputHarness.java', 'HistoryIntegrationTest.java')) {
    $interactionFiles += Join-Path $interactionTests $interactionTest
}
$interactionArgs = @('--release', '17', '-encoding', 'UTF-8', '-cp', ('"' + $interactionDeps['gson'].Replace('\', '/') + '"'), '-d', ('"' + $interactionOutput.Replace('\', '/') + '"'))
$interactionArgs += $interactionFiles | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
$interactionArgFile = Join-Path $interactionOutput 'sources.args'
[IO.File]::WriteAllLines($interactionArgFile, $interactionArgs, [Text.UTF8Encoding]::new($false))
& (Join-Path $Jdk 'bin/javac.exe') '-J-Duser.language=en' ('@' + $interactionArgFile)
if ($LASTEXITCODE -ne 0) { throw 'Java 17 interaction probe compilation failed.' }
foreach ($interactionClass in @('ui.ScrollMotionTest', 'ui.ConfigInteractionTest', 'settings.SettingsHarness', 'settings.TextInputHarness', 'settings.EntryRowsHarness', 'HistoryIntegrationTest')) {
    & (Join-Path $Jdk 'bin/java.exe') '-Duser.language=en' -ea -cp ($interactionOutput + ';' + $interactionDeps['gson']) ('com.muoniumplayer.configskija.' + $interactionClass)
    if ($LASTEXITCODE -ne 0) { throw "CPU probe failed: $interactionClass" }
}
