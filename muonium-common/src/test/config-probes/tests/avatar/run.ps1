param(
    [string]$Jdk='D:/JDK25',
    [string]$RuntimeJdk17='D:/JDK17',
    [string]$RuntimeJdk21='D:/JDK21',
    [switch]$NativePixels,
    [switch]$InspectApi
)
$ErrorActionPreference='Stop'
$avatarRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$avatarOutput=Join-Path $avatarRoot 'build/avatar'
$avatarCommon=Join-Path ([IO.Path]::GetFullPath((Join-Path $avatarRoot '../../../..'))) 'muonium-common/src/main/java/com/muoniumplayer/configskija/host/avatar'
$avatarJava=Join-Path $Jdk 'bin/java.exe'
$avatarJavac=Join-Path $Jdk 'bin/javac.exe'
$avatarJavap=Join-Path $Jdk 'bin/javap.exe'
$avatarLanes=@('1.20.1 recode','1.21.1','1.21.10','1.21.11 recode','26.1.2','26.2','26.3')
$avatarMainFiles=@(Get-ChildItem -LiteralPath $avatarCommon -Filter '*.java' | ForEach-Object FullName)
$avatarTestFiles=@(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
New-Item -ItemType Directory -Path $avatarOutput -Force | Out-Null
$avatarSummary=@()
foreach($avatarLane in $avatarLanes){
    foreach($avatarLoader in @('fabric',$(if($avatarLane -eq '1.20.1 recode'){'forge'}else{'neoforge'}))){
        $avatarCombo=Join-Path $avatarOutput "$avatarLane/$avatarLoader"
        New-Item -ItemType Directory -Path $avatarCombo -Force | Out-Null
        $avatarHost=Join-Path $avatarRoot "build/host/$avatarLane/$avatarLoader"
        $avatarCp=(Get-Content -Raw -LiteralPath (Join-Path $avatarHost 'compile-classpath.txt')).Trim()
        $avatarVersion=Join-Path ([IO.Path]::GetFullPath((Join-Path $avatarRoot '../../../..'))) "$avatarLane/src/main/java/com/muoniumplayer/configskija/host/avatar"
        $avatarVersionFiles=@(Get-ChildItem -LiteralPath $avatarVersion -Filter '*.java' | ForEach-Object FullName)
        & $avatarJavac --release 17 -encoding UTF-8 -proc:none -classpath $avatarCp -d $avatarCombo @avatarMainFiles @avatarVersionFiles @avatarTestFiles 2>&1 | Tee-Object -FilePath (Join-Path $avatarCombo 'compile.log')
        if($LASTEXITCODE -ne 0){throw "Avatar compilation failed: $avatarLane / $avatarLoader"}
        if($InspectApi){
            & $avatarJavap -classpath $avatarCp -p net.minecraft.client.Minecraft net.minecraft.client.User net.minecraft.client.player.AbstractClientPlayer net.minecraft.client.resources.SkinManager net.minecraft.client.renderer.texture.DynamicTexture net.minecraft.client.renderer.texture.TextureManager com.mojang.blaze3d.platform.NativeImage net.minecraft.util.thread.BlockableEventLoop 2>&1 | Out-File -LiteralPath (Join-Path $avatarCombo 'api-declarations.txt') -Encoding utf8
            if($LASTEXITCODE -ne 0){throw "Avatar API evidence failed: $avatarLane / $avatarLoader"}
            & $avatarJavap -classpath $avatarCp -c -p com.mojang.blaze3d.platform.NativeImage net.minecraft.util.thread.BlockableEventLoop 2>&1 | Out-File -LiteralPath (Join-Path $avatarCombo 'pixel-thread-bytecode.txt') -Encoding utf8
            if($LASTEXITCODE -ne 0){throw "Avatar pixel evidence failed: $avatarLane / $avatarLoader"}
        }
        $avatarNative='not run'
        if($NativePixels){
            $avatarRuntime=(Get-Content -Raw -LiteralPath (Join-Path $avatarHost 'runtime-classpath.txt')).Trim()
            $avatarPixelMode=if($avatarLane -in @('1.20.1 recode','1.21.1')){'ABGR'}else{'ARGB'}
            $avatarRunCp=$avatarCombo+';'+$avatarRuntime
            $avatarRuntimeJava=if($avatarLane -eq '1.20.1 recode'){Join-Path $RuntimeJdk17 'bin/java.exe'}elseif($avatarLane.StartsWith('1.21.')){Join-Path $RuntimeJdk21 'bin/java.exe'}else{$avatarJava}
            Push-Location -LiteralPath $avatarCombo
            try{& $avatarRuntimeJava --enable-native-access=ALL-UNNAMED -ea -cp $avatarRunCp com.muoniumplayer.configskija.avatar.AvatarNativePixelsTest $avatarPixelMode 2>&1 | Tee-Object -FilePath (Join-Path $avatarCombo 'native-pixels.log');$avatarCode=$LASTEXITCODE}finally{Pop-Location}
            if($avatarCode -ne 0){throw "Avatar native pixel test failed: $avatarLane / $avatarLoader"}
            $avatarNative='passed'
        }
        $avatarSummary += [pscustomobject]@{Lane=$avatarLane;Loader=$avatarLoader;Java17='passed';NativePixels=$avatarNative}
    }
}
$avatarCore=Join-Path $avatarOutput '26.3/fabric'
& $avatarJava -ea -cp $avatarCore com.muoniumplayer.configskija.avatar.AvatarContractTest ([IO.Path]::GetFullPath((Join-Path $avatarRoot '../../../..'))) $avatarCore
if($LASTEXITCODE -ne 0){throw 'Avatar API/ownership contract tests failed'}
& (Join-Path $RuntimeJdk17 'bin/java.exe') -ea -cp $avatarCore com.muoniumplayer.configskija.avatar.AvatarLoaderTest
if($LASTEXITCODE -ne 0){throw 'Avatar async behavior tests failed'}
$avatarSummary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $avatarOutput 'summary.json') -Encoding utf8
$avatarSummary | Format-Table -AutoSize
