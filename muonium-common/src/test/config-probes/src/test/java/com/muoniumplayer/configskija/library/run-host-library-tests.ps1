param([string]$Lane='26.3',[string]$Loader='fabric',[switch]$AllCombinations,[string]$Jdk='D:/JDK25')
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../../../..')).Path
& (Join-Path $PSScriptRoot 'run-library-tests.ps1')
$javaRoot=Join-Path ([IO.Path]::GetFullPath((Join-Path $projectRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija'
$sourceFiles=@((Join-Path $javaRoot 'host/MuoniumProfiles.java'),(Join-Path $javaRoot 'host/MuoniumPresets.java'),(Join-Path $javaRoot 'model/KeyBindings.java'))
$sourceFiles+=Get-ChildItem -LiteralPath (Join-Path $javaRoot 'host/library') -Filter '*.java' | ForEach-Object FullName
$sourceFiles+=Join-Path $PSScriptRoot 'HostLibraryTest.java'
$sourceFiles+=Join-Path $PSScriptRoot 'OldMusicLoaderTest.java'
$targets=@(@{Lane=$Lane;Loader=$Loader})
if($AllCombinations){$targets=@();foreach($tree in @('1.20.1 recode','1.21.1','1.21.10','1.21.11 recode','26.1.2','26.2','26.3')){foreach($targetLoader in @('fabric',$(if($tree -eq '1.20.1 recode'){'forge'}else{'neoforge'}))){$targets+=@{Lane=$tree;Loader=$targetLoader}}}}
foreach($target in $targets){
    $localOutput=Join-Path $projectRoot ('build/library-host-tests/'+$target.Lane+'/'+$target.Loader)
    New-Item -ItemType Directory -Path $localOutput -Force | Out-Null
    $classpathFile=Join-Path $projectRoot ('build/host/'+$target.Lane+'/'+$target.Loader+'/compile-classpath.txt')
    $classpath=([IO.File]::ReadAllText($classpathFile)).Trim()
    $neutral=Join-Path $projectRoot 'build/library-tests'
    $compilerArguments=@('--release','17','-encoding','UTF-8','-proc:none','-cp',('"'+($classpath+';'+$neutral).Replace('\','/')+'"'),'-d',('"'+$localOutput.Replace('\','/')+'"'))
    $compilerArguments+=$sourceFiles | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
    $compilerArgsFile=Join-Path $localOutput 'host.args'
    [IO.File]::WriteAllLines($compilerArgsFile,$compilerArguments,[Text.UTF8Encoding]::new($false))
    & (Join-Path $Jdk 'bin/javac.exe') '-J-Duser.language=en' ('@'+$compilerArgsFile)
    if($LASTEXITCODE -ne 0){throw ('Host library compilation failed: '+$target.Lane+'/'+$target.Loader)}
    Write-Output ('Host library: '+$target.Lane+'/'+$target.Loader+' COMPILE PASS')
    if($target.Lane -eq $Lane -and $target.Loader -eq $Loader){
        & (Join-Path $Jdk 'bin/java.exe') '-Dfile.encoding=UTF-8' '-ea' '-cp' ($localOutput+';'+$neutral+';'+$classpath) 'com.muoniumplayer.configskija.library.HostLibraryTest' (Join-Path $projectRoot 'build/library-host-data')
        if($LASTEXITCODE -ne 0){throw 'Host library behavior checks failed'}
        & (Join-Path $Jdk 'bin/java.exe') '-Dfile.encoding=UTF-8' '-ea' '-cp' ($localOutput+';'+$neutral+';'+$classpath) 'com.muoniumplayer.configskija.library.OldMusicLoaderTest' (Join-Path $projectRoot 'build/library-host-data')
        if($LASTEXITCODE -ne 0){throw 'Old music loader checks failed'}
    }
}
