param(
    [string]$MusicRoot = (Split-Path -Parent $PSScriptRoot),
    [string[]]$Versions = @('26.3'),
    [string[]]$Loaders = @('fabric', 'neoforge'),
    [switch]$Offline,
    [string]$HostJar
)
$ErrorActionPreference = 'Stop'
if ($HostJar) {
    if ($Versions.Count -ne 1 -or $Loaders.Count -ne 1) { throw 'HostJar requires exactly one Minecraft lane and loader.' }
    $HostJar = (Resolve-Path -LiteralPath $HostJar).Path
}
$lanes = @('1.20.1 recode','1.21.1','1.21.10','1.21.11 recode','26.1.2','26.2','26.3')
$init = Join-Path $PSScriptRoot 'gradle/host.init.gradle'
$results = @()
foreach ($lane in $Versions) {
    if ($lane -notin $lanes) { throw "Unknown host lane: $lane" }
    $tree = Join-Path $MusicRoot $lane
    if (!(Test-Path -LiteralPath (Join-Path $tree 'gradlew.bat'))) { throw "Missing Gradle wrapper: $tree" }
    foreach ($loader in $Loaders) {
        $actual = if ($lane -eq '1.20.1 recode' -and $loader -eq 'neoforge') { 'forge' } else { $loader }
        if ($actual -notin @('fabric','forge','neoforge')) { throw "Unknown loader: $actual" }
        $logDirectory = Join-Path $PSScriptRoot 'build/logs'
        New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
        $log = Join-Path $logDirectory ($lane.Replace(' ','-') + '-' + $actual + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
        Push-Location -LiteralPath $tree
        try {
            $extra = @()
            if ($Offline) { $extra += '--offline' }
            if ($HostJar) { $extra += "-PskijaConfigHostJar=$HostJar" }
            & .\gradlew.bat ":${actual}:skijaConfigBuild" ":${actual}:skijaConfigClasspath" --init-script $init "-PskijaConfigRoot=$PSScriptRoot" --no-parallel --max-workers=2 @extra 2>&1 | Tee-Object -FilePath $log
            $code = $LASTEXITCODE
        } finally { Pop-Location }
        $results += [pscustomobject]@{ Lane=$lane; Loader=$actual; ExitCode=$code; Log=$log }
        if ($code -ne 0) { $results | Format-Table; throw "Acceptance build failed: $lane / $actual (exit $code)" }
    }
}
$results | Format-Table -AutoSize
