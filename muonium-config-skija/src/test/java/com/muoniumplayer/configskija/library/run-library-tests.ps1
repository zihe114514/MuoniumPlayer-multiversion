param([string]$Jdk='D:/JDK17',[switch]$Raster,[switch]$Native)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../../../..')).Path
$javaRoot=Join-Path $projectRoot 'src/main/java/com/muoniumplayer/configskija'
$output=Join-Path $projectRoot 'build/library-tests'
$javac=Join-Path $Jdk 'bin/javac.exe'
$java=Join-Path $Jdk 'bin/java.exe'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$files=@()
$files+=Get-ChildItem -LiteralPath (Join-Path $javaRoot 'model') -Filter '*.java' | ForEach-Object FullName
$files+=Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ui') -Filter '*.java' -Recurse | Where-Object Name -ne 'SkijaPainter.java' | ForEach-Object FullName
$files+=Get-ChildItem -LiteralPath (Join-Path $javaRoot 'host/keys') -Filter '*.java' | ForEach-Object FullName
$files+=Join-Path $javaRoot 'host/HudExtensions.java'
$files+=Join-Path $PSScriptRoot 'LibraryTestContext.java'
$files+=Join-Path $PSScriptRoot 'LibraryParityTest.java'
$argumentFile=Join-Path $projectRoot 'build/library-javac.args'
$arguments=@('--release','17','-encoding','UTF-8','-d',('"'+$output.Replace('\','/')+'"'))
$arguments+=$files | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines($argumentFile,$arguments,[Text.UTF8Encoding]::new($false))
& $javac '-J-Duser.language=en' ('@'+$argumentFile)
if($LASTEXITCODE -ne 0){throw 'Library page compilation failed'}
& $java '-Dfile.encoding=UTF-8' '-ea' '-cp' $output 'com.muoniumplayer.configskija.library.LibraryParityTest'
if($LASTEXITCODE -ne 0){throw 'Library behavioral tests failed'}
if($Raster){
    $cpFile=Join-Path $projectRoot 'build/host/26.3/fabric/compile-classpath.txt'
    $entries=([IO.File]::ReadAllText($cpFile)).Trim().Split(';')
    $shared=$entries | Where-Object { [IO.Path]::GetFileName($_) -like 'skija-shared-*.jar' } | Select-Object -First 1
    $types=$entries | Where-Object { [IO.Path]::GetFileName($_) -like 'types-*.jar' } | Select-Object -First 1
    $group=[IO.Directory]::GetParent($shared).Parent.Parent.Parent.FullName
    $nativeJar=Get-ChildItem -LiteralPath (Join-Path $group 'skija-windows-x64') -Recurse -Filter '*.jar' | Where-Object Name -NotMatch 'sources|javadoc' | Select-Object -First 1
    if(!$shared -or !$types -or !$nativeJar){throw 'Exact host Skija dependencies missing'}
    $cp=@($output,(Join-Path $projectRoot 'src/main/resources'),$shared,$types,$nativeJar.FullName) -join ';'
    & $javac '-J-Duser.language=en' '--release' '17' '-encoding' 'UTF-8' '-cp' $cp '-d' $output (Join-Path $javaRoot 'ui/SkijaPainter.java') (Join-Path $PSScriptRoot 'LibraryRasterPreview.java')
    if($LASTEXITCODE -ne 0){throw 'Library raster fixture compilation failed'}
    & $java '-Dfile.encoding=UTF-8' '-ea' '-cp' $cp 'com.muoniumplayer.configskija.library.LibraryRasterPreview' (Split-Path -Parent $projectRoot) (Join-Path $projectRoot 'build/library-previews')
    if($LASTEXITCODE -ne 0){throw 'Library raster fixture rendering failed'}
}
if($Native){
    $nativeJavac='D:/JDK25/bin/javac.exe'
    $lanes=@('1.20.1 recode','1.21.1','1.21.10','1.21.11 recode','26.1.2','26.2','26.3')
    foreach($lane in $lanes){
        $other=if($lane -eq '1.20.1 recode'){'forge'}else{'neoforge'}
        foreach($loader in @('fabric',$other)){
            $cpFile=Join-Path $projectRoot ('build/host/'+$lane+'/'+$loader+'/compile-classpath.txt')
            $cp=([IO.File]::ReadAllText($cpFile)).Trim()
            $nativeOutput=Join-Path $projectRoot ('build/library-native/'+$lane+'/'+$loader)
            New-Item -ItemType Directory -Path $nativeOutput -Force | Out-Null
            $nativeFiles=@((Join-Path $javaRoot 'model/KeyBindings.java'),(Join-Path $javaRoot 'host/keys/NativeKeys.java'),(Join-Path $javaRoot 'host/keys/MinecraftKeyBindings.java'),(Join-Path $projectRoot ('src/versions/'+$lane+'/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java')))
            $nativeArgs=@('--release','17','-encoding','UTF-8','-proc:none','-classpath',('"'+$cp.Replace('\','/')+'"'),'-d',('"'+$nativeOutput.Replace('\','/')+'"'))
            $nativeArgs+=$nativeFiles | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
            $nativeArgFile=Join-Path $nativeOutput 'javac.args'
            [IO.File]::WriteAllLines($nativeArgFile,$nativeArgs,[Text.UTF8Encoding]::new($false))
            & $nativeJavac '-J-Duser.language=en' ('@'+$nativeArgFile)
            if($LASTEXITCODE -ne 0){throw ('Native key adapter compile failed: '+$lane+'/'+$loader)}
            Write-Output ('Native key adapter: '+$lane+'/'+$loader+' PASS')
        }
    }
}
