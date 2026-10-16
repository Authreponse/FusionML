@echo off
rem FusionML build wrapper.
rem Why this exists: Windows PowerShell blocks .ps1 files by execution policy
rem (SecurityError: UnauthorizedAccess). .cmd files are not affected, and we pass
rem -ExecutionPolicy Bypass for this one invocation only - no system setting is changed.
rem Usage:  build.cmd            build
rem         build.cmd -Clean     clean build
rem         build.cmd -RunBench  build then run self-test + benchmark
setlocal
set "PS=powershell"
where pwsh >nul 2>nul && set "PS=pwsh"
%PS% -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
if errorlevel 1 (
  echo.
  echo [build] FAILED
  exit /b 1
)
endlocal
