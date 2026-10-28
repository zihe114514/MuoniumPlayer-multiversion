@echo off
setlocal

set "SCRIPT=%~dp0tools\bump-version.ps1"
if not exist "%SCRIPT%" (
    echo Script not found: "%SCRIPT%" 1>&2
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
exit /b %ERRORLEVEL%
