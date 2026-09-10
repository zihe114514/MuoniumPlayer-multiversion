@echo off
setlocal

set "SCRIPT=%~dp0tools\build-all.ps1"
if not exist "%SCRIPT%" (
    echo Build script not found: "%SCRIPT%" 1>&2
    exit /b 1
)

rem Use -Command instead of -File so PowerShell can parse array arguments
rem such as -Versions '1.20.1 recode','26.2' correctly.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '%SCRIPT%' %*"
exit /b %ERRORLEVEL%
