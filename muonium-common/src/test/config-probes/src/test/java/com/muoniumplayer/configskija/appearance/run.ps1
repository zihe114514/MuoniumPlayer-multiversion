param(
    [string]$Jdk = 'D:/JDK17',
    [string]$ReferenceRoot = 'E:/oneconfig lite/MuoniumConfig-src',
    [switch]$ComposeReference
)
$ErrorActionPreference = 'Stop'
$appearanceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../../../'))
$appearanceSourceRoot = Join-Path ([IO.Path]::GetFullPath((Join-Path $appearanceRoot '../../../..'))) 'muonium-common/src/main/java'
$appearancePackage = Join-Path $appearanceSourceRoot 'com/muoniumplayer/configskija'
$appearanceOutput = Join-Path $appearanceRoot 'build/appearance-tests'
$appearanceJavac = Join-Path $Jdk 'bin/javac.exe'
$appearanceJava = Join-Path $Jdk 'bin/java.exe'
New-Item -ItemType Directory -Path $appearanceOutput -Force | Out-Null
$appearanceFiles = @(Get-ChildItem -LiteralPath (Join-Path $appearancePackage 'ui/pages') -Filter 'Appearance*.java' | ForEach-Object { $_.FullName })
$appearanceFiles += Join-Path $appearancePackage 'ui/Theme.java'
$appearanceFiles += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object { $_.FullName }
& $appearanceJavac --release 17 -encoding UTF-8 -sourcepath $appearanceSourceRoot -d $appearanceOutput @appearanceFiles
if ($LASTEXITCODE -ne 0) { throw 'Appearance Java 17 compilation failed' }
& $appearanceJava -ea -cp $appearanceOutput com.muoniumplayer.configskija.appearance.AppearanceParityTest $ReferenceRoot
if ($LASTEXITCODE -ne 0) { throw 'Appearance behavior/source parity tests failed' }
if ($ComposeReference) {
    $appearanceCache = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' } else { Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1' }
    $appearanceVersions = Get-Content -Raw -LiteralPath (Join-Path $ReferenceRoot 'gradle/libs.versions.toml')
    $appearanceComposeVersion = [regex]::Match($appearanceVersions, '(?m)^compose = "([^"
]+)"').Groups[1].Value
    $appearanceKotlinVersion = [regex]::Match($appearanceVersions, '(?m)^kotlin = "([^"
]+)"').Groups[1].Value
    if (!$appearanceComposeVersion -or !$appearanceKotlinVersion) { throw 'Original Compose/Kotlin versions were not found' }
    $appearanceCoordinates = @(
        "org.jetbrains.compose.ui/ui-graphics-desktop/$appearanceComposeVersion",
        "org.jetbrains.compose.ui/ui-util-desktop/$appearanceComposeVersion",
        "org.jetbrains.compose.animation/animation-core-desktop/$appearanceComposeVersion",
        "org.jetbrains.kotlin/kotlin-stdlib/$appearanceKotlinVersion",
        'androidx.collection/collection-jvm/1.6.0'
    )
    $appearanceJars = foreach ($coordinate in $appearanceCoordinates) {
        $artifact = Get-ChildItem -LiteralPath (Join-Path $appearanceCache $coordinate) -Filter '*.jar' -Recurse | Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } | Select-Object -First 1
        if (!$artifact) { throw "Missing resolved reference jar: $coordinate" }
        $artifact.FullName
    }
    $appearanceClasspath = $appearanceOutput + [System.IO.Path]::PathSeparator + ($appearanceJars -join [System.IO.Path]::PathSeparator)
    & $appearanceJava -ea -cp $appearanceClasspath com.muoniumplayer.configskija.appearance.AppearanceComposeReferenceTest
    if ($LASTEXITCODE -ne 0) { throw 'Appearance Compose reference comparison failed' }
}
