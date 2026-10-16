@echo off
rem FusionML test wrapper - same reason as build.cmd.
rem Usage:  test.cmd                         前置分析自检（快）
rem         test.cmd -Mode launch -WaitSeconds 110    启动游戏，到点自动终止并打印关键日志
setlocal
set "PS=powershell"
where pwsh >nul 2>nul && set "PS=pwsh"
%PS% -NoProfile -ExecutionPolicy Bypass -File "%~dp0test.ps1" %*
endlocal
