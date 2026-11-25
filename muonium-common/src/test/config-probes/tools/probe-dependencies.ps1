# Read exact dependency declarations; use only existing cache files, never Gradle/downloads.
function Get-ProbeDependencies {
    param([string]$MusicRoot, [string]$Lane = '26.3', [string]$Loader = 'fabric')
    $declarations = Get-Content -Raw -LiteralPath (Join-Path $MusicRoot "$Lane/$Loader/build.gradle")
    $cache = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' } else { Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1' }
    $resolved = @{}
    foreach ($coordinate in @('com.google.code.gson:gson', 'io.github.humbleui:skija-shared', 'io.github.humbleui:types', 'io.github.humbleui:skija-windows-x64')) {
        $match = [regex]::Match($declarations, [regex]::Escape($coordinate) + ':([0-9][^''"\s]*)')
        if (!$match.Success) { throw "No literal dependency declaration for $coordinate in $Lane/$Loader; supply and verify a current host classpath before extending this probe." }
        $version = $match.Groups[1].Value
        $parts = $coordinate.Split(':')
        $directory = Join-Path $cache ($parts[0] + '/' + $parts[1] + '/' + $version)
        $name = $parts[1] + '-' + $version + '.jar'
        $jar = Get-ChildItem -LiteralPath $directory -Recurse -Filter $name -File | Select-Object -First 1
        if (!$jar) { throw "Exact dependency is not cached: ${coordinate}:$version. This tool never starts Gradle or downloads files." }
        $resolved[$parts[1]] = $jar.FullName
    }
    return $resolved
}
