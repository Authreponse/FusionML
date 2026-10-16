<#
  FusionML build script.

  Deliberately dependency-free: javac + jar from the JDK only (no Gradle, no Maven,
  no network). The whole loader is built from this one file.

  NOTE: this script is intentionally ASCII-only. Windows PowerShell 5.1 reads .ps1
  files using the ANSI code page unless the file starts with a UTF-8 BOM, so non-ASCII
  text here would turn into syntax errors on Chinese/Japanese Windows.
  (The program itself prints Chinese text; that is handled in Java, not here.)
#>
param(
    [string]$JdkHome = "",
    [switch]$Clean,
    [switch]$RunBench
)

$ErrorActionPreference = "Stop"
$root    = $PSScriptRoot
$srcMain = Join-Path $root "src\main\java"
$srcTest = Join-Path $root "src\test\java"
$resMain = Join-Path $root "src\main\resources"
$outMain = Join-Path $root "build\classes"
$outTest = Join-Path $root "build\test-classes"
$outJar  = Join-Path $root "build\fusion-ml.jar"
$outInstaller = Join-Path $root "build\fusion-installer.jar"

function Resolve-Jdk {
    param([string]$explicit)
    if ($explicit -ne "") {
        if (Test-Path (Join-Path $explicit "bin\javac.exe")) { return $explicit }
        throw "Invalid JDK home: $explicit"
    }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $j = (Get-Command javac.exe -ErrorAction SilentlyContinue)
    if ($j) { $candidates += (Split-Path (Split-Path $j.Source -Parent) -Parent) }
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "bin\javac.exe")) { return $c }
    }
    throw "Cannot find javac. Pass it explicitly: .\build.ps1 -JdkHome <path>"
}

$jdk   = Resolve-Jdk -explicit $JdkHome
$javac = Join-Path $jdk "bin\javac.exe"
$jar   = Join-Path $jdk "bin\jar.exe"
$java  = Join-Path $jdk "bin\java.exe"
Write-Host "[build] JDK: $jdk" -ForegroundColor Cyan

if ($Clean -and (Test-Path (Join-Path $root "build"))) {
    Remove-Item -LiteralPath (Join-Path $root "build") -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $outMain | Out-Null
New-Item -ItemType Directory -Force -Path $outTest | Out-Null

# javac @argfile notes:
#   * backslash is an escape character inside argfiles, so paths are written with
#     forward slashes (Windows javac accepts them);
#   * the file must be UTF-8 without BOM, otherwise non-ASCII directory names break.
function Write-ArgFile {
    param([string]$path, [string[]]$lines)
    $sb = New-Object System.Text.StringBuilder
    foreach ($l in $lines) { [void]$sb.AppendLine($l) }
    [System.IO.File]::WriteAllText($path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
}

function ConvertTo-JavacPath {
    param([string]$p)
    return '"' + $p.Replace('\', '/') + '"'
}

$mainFiles = @(Get-ChildItem -Path $srcMain -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($mainFiles.Count -eq 0) { throw "No main sources found under $srcMain" }

$argFile = Join-Path $root "build\javac-main.args"
$lines = @("-d " + (ConvertTo-JavacPath $outMain), "-encoding UTF-8", "-source 21", "-target 21",
           "-Xlint:-options")
$lines += ($mainFiles | ForEach-Object { ConvertTo-JavacPath $_ })
Write-ArgFile -path $argFile -lines $lines

Write-Host "[build] Compiling main sources ($($mainFiles.Count) files)..." -ForegroundColor Cyan
& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Main compilation failed" }

# Resources must land in the classes dir as well, otherwise ServiceLoader cannot
# discover the built-in early-window providers.
if (Test-Path $resMain) {
    Copy-Item -Recurse -Force (Join-Path $resMain "*") $outMain
    Write-Host "[build] Resources copied." -ForegroundColor Cyan
}

$testFiles = @()
if (Test-Path $srcTest) {
    $testFiles = @(Get-ChildItem -Path $srcTest -Recurse -Filter *.java | ForEach-Object { $_.FullName })
}
if ($testFiles.Count -gt 0) {
    $targ = Join-Path $root "build\javac-test.args"
    $tlines = @("-d " + (ConvertTo-JavacPath $outTest), "-encoding UTF-8", "-source 21", "-target 21",
                "-cp " + (ConvertTo-JavacPath $outMain))
    $tlines += ($testFiles | ForEach-Object { ConvertTo-JavacPath $_ })
    Write-ArgFile -path $targ -lines $tlines
    Write-Host "[build] Compiling tests/bench ($($testFiles.Count) files)..." -ForegroundColor Cyan
    & $javac "@$targ"
    if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }
}

Push-Location $outMain
$manifest = Join-Path $root "build\MANIFEST.MF"
@(
    "Manifest-Version: 1.0",
    "Main-Class: com.fusionml.FusionLauncher",
    "Premain-Class: com.fusionml.launch.FusionAgent",
    "Can-Retransform-Classes: false",
    "Implementation-Title: FusionML",
    "Implementation-Version: 1.0.0",
    ""
) -join "`r`n" | Set-Content -Path $manifest -Encoding ASCII
& $jar --create --file $outJar --manifest $manifest .
Pop-Location
Write-Host "[build] Artifact: $outJar" -ForegroundColor Green

# ---------------------------------------------------------------------------
# Per-version loader jars.
#
# The game versions differ in ways that cannot be inferred at runtime (required Java
# major: 26.x wants 25, 1.20.1 wants 17; and whether the game jar is obfuscated at
# all). Baking those into code means "either all right or all wrong". Instead each jar
# carries its own factory defaults as manifest attributes, and every one of them stays
# overridable by an explicit -D property.
#
# Naming: fusion-ml-<target>.jar  e.g. fusion-ml-26.3.jar, fusion-ml-1.20.1.jar
# The generic fusion-ml.jar is still produced above (unbound; keeps old behaviour).
#
# NOTE: this block runs BEFORE the generic installer is packaged, because that single
# installer embeds all five loader jars (see below). Previously the per-version
# installers each embedded their own loader, so the order did not matter.
$targets = @(
    @{ Id = "26.3";   Java = "25" },
    @{ Id = "26.2";   Java = "25" },
    @{ Id = "26.1.2"; Java = "25" },
    @{ Id = "1.21.1"; Java = "21" },
    @{ Id = "1.20.1"; Java = "17" }
)
foreach ($t in $targets) {
    $name = "fusion-ml-" + $t.Id + ".jar"
    $out  = Join-Path $root ("build\" + $name)
    $mf   = Join-Path $root ("build\MANIFEST-" + $t.Id + ".mf")
    @(
        "Manifest-Version: 1.0",
        "Main-Class: com.fusionml.FusionLauncher",
        "Premain-Class: com.fusionml.launch.FusionAgent",
        "Can-Retransform-Classes: false",
        "Implementation-Title: FusionML",
        "Implementation-Version: 1.0.0",
        "FusionML-Target: $($t.Id)",
        "FusionML-RequiredJava: $($t.Java)",
        ""
    ) -join "`r`n" | Set-Content -Path $mf -Encoding ASCII
    Push-Location $outMain
    & $jar --create --file $out --manifest $mf .
    Pop-Location
    if ($LASTEXITCODE -ne 0) { throw "Failed to build $name" }
    Write-Host "[build] Artifact: $out" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Installer jar (SINGLE, all versions).
#
# Everything from the loader build PLUS every per-version loader jar embedded as a
# resource. The GUI lets the player pick a version, and extractLoader() pulls the
# matching fusion-ml-<id>.jar out of META-INF/fusionml/loaders/. That is what makes
# "one installer for all versions" actually correct: embedding only the generic
# loader would deploy a jar without the version's Java/platform defaults.
$stage = Join-Path $root "build\installer-staging"
$stageRes = Join-Path $stage "META-INF\fusionml"
$stageResAll = Join-Path $stageRes "loaders"
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stageResAll | Out-Null
Copy-Item -Recurse -Force (Join-Path $outMain "*") $stage
Copy-Item -Force $outJar (Join-Path $stageRes "fusion-ml.jar")
foreach ($t in $targets) {
    Copy-Item -Force (Join-Path $root ("build\fusion-ml-" + $t.Id + ".jar")) `
        (Join-Path $stageResAll ("fusion-ml-" + $t.Id + ".jar"))
}
Push-Location $stage
& $jar --create --file $outInstaller --main-class com.fusionml.install.FusionInstaller .
Pop-Location
Write-Host "[build] Artifact: $outInstaller" -ForegroundColor Green


if ($RunBench) {
    Write-Host "[build] Running self-test and benchmark..." -ForegroundColor Cyan
    & $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "$outMain;$outTest" `
        com.fusionml.bytecode.BytecodeSelfTest
    & $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" -cp "$outMain;$outTest" `
        com.fusionml.bench.LoadBenchmark --mods (Join-Path $root "build\fixtures")
}
