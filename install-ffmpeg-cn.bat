@echo off
title FFmpeg 一键安装（国内镜像版）
setlocal EnableExtensions
rem ============================================================
rem  FFmpeg 一键安装脚本（国内镜像源 + 自动写入 PATH）
rem
rem  用法:
rem    install-ffmpeg-cn.bat             交互安装（20 秒无操作自动继续）
rem    install-ffmpeg-cn.bat --force     静默安装，不询问、不暂停
rem    install-ffmpeg-cn.bat --system    额外写入系统 PATH（需管理员）
rem    两个参数可同时使用:  install-ffmpeg-cn.bat --force --system
rem
rem  自定义安装目录（可选）:
rem    set FF_INSTALL_DIR=D:\tools\ffmpeg  然后再运行本脚本
rem    默认安装到: %%LOCALAPPDATA%%\Programs\ffmpeg
rem
rem  下载源（自动按序回退）:
rem    源1  npmmirror(阿里云)   FFmpeg 6.1.1 静态版 ffmpeg+ffprobe
rem    源2  gh-proxy.com 加速   BtbN 最新构建 (含 ffplay)
rem    源3  ghproxy.net 加速    BtbN 最新构建
rem    源4  GitHub 直连          BtbN 最新构建
rem
rem  PATH 写入说明:
rem    使用 reg add 保留 REG_EXPAND_SZ 类型，不会破坏 PATH 中已有的
rem    %VAR% 未展开变量；写入后广播 WM_SETTINGCHANGE，新开的终端
rem    立即可用，无需注销重启。
rem ============================================================

set "FORCE="
set "SYS=0"
:ARGLOOP
if "%~1"=="" goto ARGS_DONE
if /i "%~1"=="--force"  set "FORCE=1"
if /i "%~1"=="-f"       set "FORCE=1"
if /i "%~1"=="--system" set "SYS=1"
shift
goto ARGLOOP
:ARGS_DONE

set "NPM_VER=b6.1.1"
set "BTBN_URL=https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
if not defined FF_INSTALL_DIR set "FF_INSTALL_DIR=%LOCALAPPDATA%\Programs\ffmpeg"
if "%FF_INSTALL_DIR:~-1%"=="\" set "FF_INSTALL_DIR=%FF_INSTALL_DIR:~0,-1%"
set "FF_BIN=%FF_INSTALL_DIR%\bin"
set "TMPD="
set "INSTALLED=0"
set "SRC_DESC="

echo.
echo  ============================================
echo    FFmpeg 一键安装 (国内镜像加速)
echo  ============================================
echo    安装目录: %FF_INSTALL_DIR%
echo.

set "CURL=%SystemRoot%\System32\curl.exe"
set "TAR=%SystemRoot%\System32\tar.exe"
if not exist "%CURL%" set "CURL=curl.exe"
if not exist "%TAR%" set "TAR=tar.exe"

rem ---------- 前置检查 ----------
if not exist "%SystemRoot%\System32\curl.exe" (
    echo  [错误] 未找到 curl.exe（Windows 10 1803+ 自带），请在 Win10 以上系统运行。
    goto FAIL
)
if not exist "%SystemRoot%\System32\tar.exe" (
    echo  [错误] 未找到 tar.exe（Windows 10 1803+ 自带），请在 Win10 以上系统运行。
    goto FAIL
)

where ffmpeg >nul 2>&1
if errorlevel 1 goto ASK
echo  [提示] 检测到系统中已安装 FFmpeg:
ffmpeg -version 2>&1 | findstr /b /i "ffmpeg version"
echo         本脚本将安装/覆盖到: %FF_INSTALL_DIR%
:ASK
if defined FORCE goto ASK_DONE
choice /c YN /t 20 /d Y /m "  继续安装并写入 PATH? (20 秒后自动继续[Y]，按 N 取消)"
if errorlevel 2 goto CANCEL
:ASK_DONE

set "TMPD=%LOCALAPPDATA%\Temp\ffmpeg-install-%RANDOM%%RANDOM%"
md "%TMPD%" >nul 2>&1
md "%FF_BIN%" >nul 2>&1

rem ---------- 下载 ----------
echo.
echo  [1/4] 下载 FFmpeg ...

rem --- 源1: npmmirror 阿里云 (静态单文件, 体积小速度最快) ---
echo         源1: npmmirror 阿里云 (FFmpeg %NPM_VER:~1% 静态版) ...
call :GETFILE "https://registry.npmmirror.com/-/binary/ffmpeg-static/%NPM_VER%/ffmpeg-win32-x64" "%FF_BIN%\ffmpeg.exe.new"
if errorlevel 1 goto SRC2
for %%F in ("%FF_BIN%\ffmpeg.exe.new") do if %%~zF LSS 20000000 goto SRC2
call :GETFILE "https://registry.npmmirror.com/-/binary/ffmpeg-static/%NPM_VER%/ffprobe-win32-x64" "%FF_BIN%\ffprobe.exe.new"
if errorlevel 1 goto SRC2
for %%F in ("%FF_BIN%\ffprobe.exe.new") do if %%~zF LSS 20000000 goto SRC2
"%FF_BIN%\ffmpeg.exe.new" -version 2>nul | findstr /i /c:"ffmpeg version" >nul
if errorlevel 1 goto SRC2
"%FF_BIN%\ffprobe.exe.new" -version 2>nul | findstr /i /c:"ffprobe version" >nul
if errorlevel 1 goto SRC2
move /y "%FF_BIN%\ffmpeg.exe.new"  "%FF_BIN%\ffmpeg.exe"  >nul 2>&1
if errorlevel 1 goto FILE_LOCKED
move /y "%FF_BIN%\ffprobe.exe.new" "%FF_BIN%\ffprobe.exe" >nul 2>&1
if errorlevel 1 goto FILE_LOCKED
set "INSTALLED=1"
set "SRC_DESC=npmmirror 阿里云镜像 (FFmpeg %NPM_VER:~1% 静态构建)"
goto DL_DONE

rem --- 源2: gh-proxy.com 加速 GitHub BtbN 最新构建 ---
:SRC2
if "%INSTALLED%"=="1" goto DL_DONE
echo         源2: gh-proxy.com 加速 GitHub BtbN 最新构建 (含 ffplay) ...
call :GETFILE "https://gh-proxy.com/%BTBN_URL%" "%TMPD%\btbn.zip"
if errorlevel 1 goto SRC3
for %%F in ("%TMPD%\btbn.zip") do if %%~zF LSS 50000000 goto SRC3
"%TAR%" -xf "%TMPD%\btbn.zip" -C "%TMPD%" >nul 2>&1
if errorlevel 1 goto SRC3
set "COPIED="
for /d %%D in ("%TMPD%\ffmpeg-*") do (
    xcopy /y /q "%%D\bin\*.exe" "%FF_BIN%\" >nul 2>&1 && set "COPIED=1"
)
if not defined COPIED goto SRC3
set "INSTALLED=1"
set "SRC_DESC=GitHub BtbN 最新构建 (经 gh-proxy.com 加速)"
goto DL_DONE

rem --- 源3: ghproxy.net 加速 ---
:SRC3
if "%INSTALLED%"=="1" goto DL_DONE
echo         源3: ghproxy.net 加速 GitHub BtbN 最新构建 ...
call :GETFILE "https://ghproxy.net/%BTBN_URL%" "%TMPD%\btbn.zip"
if errorlevel 1 goto SRC3
for %%F in ("%TMPD%\btbn.zip") do if %%~zF LSS 50000000 goto SRC3
"%TAR%" -xf "%TMPD%\btbn.zip" -C "%TMPD%" >nul 2>&1
if errorlevel 1 goto SRC3
set "COPIED="
for /d %%D in ("%TMPD%\ffmpeg-*") do (
    xcopy /y /q "%%D\bin\*.exe" "%FF_BIN%\" >nul 2>&1 && set "COPIED=1"
)
if not defined COPIED goto SRC3
set "INSTALLED=1"
set "SRC_DESC=GitHub BtbN 最新构建 (经 ghproxy.net 加速)"
goto DL_DONE

rem --- 源3: gh-proxy.com 加速 ---
:SRC3
if "%INSTALLED%"=="1" goto DL_DONE
echo         源3: gh-proxy.com 加速 GitHub BtbN 最新构建 ...
call :GETFILE "https://gh-proxy.com/%BTBN_URL%" "%TMPD%\btbn.zip"
if errorlevel 1 goto SRC4
for %%F in ("%TMPD%\btbn.zip") do if %%~zF LSS 50000000 goto SRC4
"%TAR%" -xf "%TMPD%\btbn.zip" -C "%TMPD%" >nul 2>&1
if errorlevel 1 goto SRC4
set "COPIED="
for /d %%D in ("%TMPD%\ffmpeg-*") do (
    xcopy /y /q "%%D\bin\*.exe" "%FF_BIN%\" >nul 2>&1 && set "COPIED=1"
)
if not defined COPIED goto SRC4
set "INSTALLED=1"
set "SRC_DESC=GitHub BtbN 最新构建 (经 ghproxy.net 加速)"
goto DL_DONE

rem --- 源4: GitHub 直连 ---
:SRC4
if "%INSTALLED%"=="1" goto DL_DONE
echo         源4: GitHub 直连 BtbN 最新构建 ...
call :GETFILE "%BTBN_URL%" "%TMPD%\btbn.zip"
if errorlevel 1 goto DL_FAIL
for %%F in ("%TMPD%\btbn.zip") do if %%~zF LSS 50000000 goto DL_FAIL
"%TAR%" -xf "%TMPD%\btbn.zip" -C "%TMPD%" >nul 2>&1
if errorlevel 1 goto DL_FAIL
set "COPIED="
for /d %%D in ("%TMPD%\ffmpeg-*") do (
    xcopy /y /q "%%D\bin\*.exe" "%FF_BIN%\" >nul 2>&1 && set "COPIED=1"
)
if not defined COPIED goto DL_FAIL
set "INSTALLED=1"
set "SRC_DESC=GitHub BtbN 最新构建 (直连)"
goto DL_DONE

:DL_FAIL
if "%INSTALLED%"=="1" goto DL_DONE
echo  [错误] 所有下载源均失败，请检查网络后重新运行。
goto FAIL

:DL_DONE
if not "%INSTALLED%"=="1" (
    echo  [错误] 所有下载源均失败，请检查网络后重新运行。
    goto FAIL
)
del "%FF_BIN%\*.new" >nul 2>&1

rem ---------- 校验 ----------
echo  [2/4] 校验二进制 ...
"%FF_BIN%\ffmpeg.exe" -version 2>nul | findstr /i /c:"ffmpeg version" >nul
if errorlevel 1 (
    echo  [错误] ffmpeg.exe 校验失败，下载可能损坏。
    goto FAIL
)
"%FF_BIN%\ffprobe.exe" -version 2>nul | findstr /i /c:"ffprobe version" >nul
if errorlevel 1 (
    echo  [错误] ffprobe.exe 校验失败，下载可能损坏。
    goto FAIL
)
echo         校验通过

rem ---------- 写 PATH ----------
echo  [3/4] 写入用户 PATH ...
call :PatchPath "HKCU\Environment"
if "%SYS%"=="1" (
    net session >nul 2>&1
    if errorlevel 1 (
        echo  [警告] 未以管理员身份运行，跳过系统 PATH 写入。
    ) else (
        echo        写入系统 PATH ...
        call :PatchPath "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment"
    )
)

rem ---------- 广播环境变量变更 ----------
echo  [4/4] 通知系统刷新环境变量 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$sig='[DllImport(\"user32.dll\",SetLastError=true,CharSet=CharSet.Auto)]public static extern IntPtr SendMessageTimeout(IntPtr hWnd,uint Msg,UIntPtr wParam,string lParam,uint fuFlags,uint uTimeout,out UIntPtr lpdwResult);';$t=Add-Type -MemberDefinition $sig -Name W -Namespace K -PassThru;[UIntPtr]$r=[UIntPtr]::Zero;[void]$t::SendMessageTimeout([IntPtr]0xffff,0x1A,[UIntPtr]::Zero,'Environment',2,5000,[ref]$r)" >nul 2>&1

rem 当前脚本会话内立即生效
set "PATH=%PATH%;%FF_BIN%"

> "%FF_INSTALL_DIR%\install-source.txt" echo %SRC_DESC%  (%DATE% %TIME%)

echo.
echo  ============================================
echo    安装完成!
echo    来源:   %SRC_DESC%
echo    位置:   %FF_BIN%
"%FF_BIN%\ffmpeg.exe" -version 2>&1 | findstr /b /i "ffmpeg version"
echo  ============================================
echo    请打开【新的】终端窗口后运行 ffmpeg -version 验证。
echo.
rd /s /q "%TMPD%" >nul 2>&1
if defined FORCE exit /b 0
pause
exit /b 0

rem ---------- 子程序 ----------
:PatchPath
rem %1 = 注册表键; 读取原始 PATH(不展开 %VAR%), 已存在则跳过, 否则追加
set "RAWPATH="
for /f "skip=2 tokens=1,2,*" %%A in ('reg query "%~1" /v Path 2^>nul') do (
    if /i "%%A"=="Path" set "RAWPATH=%%C"
)
if not defined RAWPATH (
    set "NEWPATH=%FF_BIN%"
    goto PatchPath_Write
)
rem 用子串替换判断是否已包含（findstr 默认按正则解析路径中的 \U \f 等序列，不可靠）
set "CHK=%RAWPATH%"
call set "CHK=%%CHK:%FF_BIN%=%%%"
if /i not "%CHK%"=="%RAWPATH%" goto PatchPath_Skip
set "NEWPATH=%RAWPATH%;%FF_BIN%"
goto PatchPath_Write
:PatchPath_Skip
echo        PATH 中已存在 %FF_BIN%，跳过
set "RAWPATH="
goto PatchPath_Clean
:PatchPath_Write
reg add "%~1" /v Path /t REG_EXPAND_SZ /d "%NEWPATH%" /f >nul 2>&1
if errorlevel 1 (
    echo  [警告] 写入 %~1 失败。
) else (
    echo        已写入 %~1
)
:PatchPath_Clean
set "RAWPATH="
set "NEWPATH="
exit /b 0

:GETFILE
rem %1 = URL, %2 = 输出文件
"%CURL%" -fL --connect-timeout 15 --retry 2 --retry-delay 2 -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" -o "%~2" "%~1"
exit /b %errorlevel%

rem ---------- 结束分支 ----------
:FILE_LOCKED
echo  [错误] 目标文件被占用（可能 ffmpeg/ffprobe 正在运行），请关闭相关程序后重试。
goto FAIL

:CANCEL
rd /s /q "%TMPD%" >nul 2>&1
echo   已取消，未做任何更改。
if defined FORCE exit /b 0
pause
exit /b 0

:FAIL
rd /s /q "%TMPD%" >nul 2>&1
echo.
if defined FORCE exit /b 1
pause
exit /b 1
