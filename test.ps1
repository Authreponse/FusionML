# FusionML test helper: build, run one scenario, show only the lines that matter.
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 in the ANSI code page
# unless it starts with a UTF-8 BOM, so non-ASCII here would break the script.
param(
    [ValidateSet("boot", "launch", "check")]
    [string]$Mode = "boot",
    [int]$WaitSeconds = 100,
    # Empty means "read build\gamedir.txt (UTF-8)". The path contains CJK characters,
    # and this script is ANSI-read by PowerShell 5.1, so a literal here would be mangled.
    [string]$GameDir = "",
    [string]$McVersion = "26.3-Fabric 0.19.5",
    # Lets two scenarios run side by side without overwriting each other's logs.
    [string]$LogPrefix = "test"
)

$root = $PSScriptRoot
if (-not $GameDir) {
    $GameDir = (Get-Content (Join-Path $root "build\gamedir.txt") -Encoding UTF8 -Raw).Trim()
}
$jar = Join-Path $root "build\fusion-ml.jar"
$log = Join-Path $root ("build\" + $LogPrefix + ".out")
$err = Join-Path $root ("build\" + $LogPrefix + ".err")

Write-Host "[test] building..." -ForegroundColor Cyan
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "build.ps1") 2>&1 |
    Select-String -Pattern "error:|Artifact" | Select-Object -Last 3

Remove-Item $log, $err -ErrorAction SilentlyContinue

if ($Mode -eq "check") {
    java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -jar $jar --mc-dir $GameDir `
        --mc-version $McVersion --mc-check > $log 2> $err
} else {
    $switch = if ($Mode -eq "launch") { "--mc-launch" } else { "--mc-boot" }
    $argList = @('"-Dfile.encoding=UTF-8"', '"-Dstdout.encoding=UTF-8"', '-jar', $jar,
                 '--mc-dir', ('"' + $GameDir + '"'), '--mc-version', ('"' + $McVersion + '"'),
                 $switch)
    $p = Start-Process -FilePath "java" -ArgumentList $argList -RedirectStandardOutput $log `
        -RedirectStandardError $err -PassThru -WindowStyle Hidden
    Write-Host "[test] pid=$($p.Id) mode=$Mode"
    Start-Sleep -Seconds $WaitSeconds
    $alive = -not $p.HasExited
    if ($alive) { taskkill /PID $p.Id /T /F 2>&1 | Out-Null }
    Write-Host "[test] still-running-after-$($WaitSeconds)s = $alive (True means the game was up)"
}

Write-Host "--- milestones ---" -ForegroundColor Cyan
Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue |
    Select-String -Pattern "已引导|引导未完成|已接管平台启动器|Loading \d+ mods|Setting user|Backend library|OpenGL|Reloading ResourceManager|Sound engine|Stopping|注入自检|模组加载完成" |
    Select-Object -First 14 | ForEach-Object { $_.Line }

Write-Host "--- failures ---" -ForegroundColor Yellow
foreach ($f in @($log, $err)) {
    Get-Content $f -Encoding UTF8 -ErrorAction SilentlyContinue |
        Select-String -Pattern "· |Exception|Caused by|ERROR|NullPointer|Duplicate" |
        Select-Object -First 8 | ForEach-Object { $_.Line }
}

Write-Host "--- mod activity ---" -ForegroundColor Cyan
Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue |
    Select-String -Pattern "Sodium|Iris|Axiom|Initializing|initializ" |
    Select-Object -First 8 | ForEach-Object { $_.Line }
