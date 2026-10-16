@echo off
rem Wrapper: PowerShell execution policy blocks .ps1 on this machine (SecurityError).
setlocal
set "PS=powershell"
where pwsh >nul 2>nul && set "PS=pwsh"
%PS% -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-probe.ps1" %*
if errorlevel 1 exit /b 1
endlocal
