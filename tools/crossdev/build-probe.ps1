# Builds the cross-platform probe mod: a real NeoForge-format jar carrying a real Mixin.
#
# Why a real Mixin instead of our own injector: the claim being tested is that a mod jar
# sitting in mods/ that belongs to *another* loader ecosystem can join the host loader's
# run (same class loader, same Mixin service). Only the host's own Mixin can prove that.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 in the ANSI code page unless a
# UTF-8 BOM is present, so non-ASCII here would be mangled. Paths with CJK characters come
# from build\gamedir.txt instead of being written literally.
param(
    [string]$ModsDir = ""
)

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $ModsDir) {
    $gameDir = (Get-Content (Join-Path $root "build\gamedir.txt") -Encoding UTF8 -Raw).Trim()
    $ModsDir = Join-Path $gameDir "mods"
}

$zulu = "C:\Program Files\Zulu\zulu-25\bin"
$javac = Join-Path $zulu "javac.exe"
$jartool = Join-Path $zulu "jar.exe"
$mixinJar = "D:\" + [char]0x6211 + [char]0x7684 + [char]0x4E16 + [char]0x754C +
    "\" + [char]0x670D + [char]0x52A1 + [char]0x5668 + "\" +
    ".minecraft\libraries\net\fabricmc\sponge-mixin\0.17.4+mixin.0.8.7\sponge-mixin-0.17.4+mixin.0.8.7.jar"

# The probe also carries a real @Mod entrypoint / DeferredRegister / @EventBusSubscriber.
# Those need the other platform's API on the compile classpath, and the game jar for the
# vanilla types they touch. Everything is derived from the game dir (which may contain
# CJK characters, hence reading it from the file instead of writing it literally).
$libRoot = Join-Path (Split-Path (Split-Path $gameDir -Parent) -Parent) "libraries"
$versionsRoot = Split-Path $gameDir -Parent
function Find-NewestJar {
    param([string]$pattern)
    $hit = Get-ChildItem -Recurse -File -Path $libRoot -Filter $pattern -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1
    if ($hit) { return $hit.FullName }
    return $null
}

# Classpath = the libraries declared by this instance's version json, plus the ones from a
# sibling implementation of the *other* platform (that is where the probe's @Mod API lives).
# Reading the version json is the only reliable way: the probe needs all the vanilla
# transitive types its code touches (datafixerupper, brigadier, ...).
function Add-VersionJars {
    param([System.Collections.ArrayList]$list, [string]$jsonPath)
    if (-not (Test-Path $jsonPath)) { return }
    $j = Get-Content $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($lib in $j.libraries) {
        $rel = $null
        if ($lib.downloads -and $lib.downloads.artifact) { $rel = $lib.downloads.artifact.path }
        if (-not $rel) { continue }
        $p = Join-Path $libRoot ($rel -replace '/', '\')
        if (Test-Path $p) { [void]$list.Add($p) }
    }
}
$compileCp = New-Object System.Collections.ArrayList
[void]$compileCp.Add($mixinJar)
$mcJar = Get-ChildItem -File -Path $gameDir -Filter *.jar | Select-Object -First 1
if ($mcJar) { [void]$compileCp.Add($mcJar.FullName) }
$versionId = Split-Path $gameDir -Leaf
Add-VersionJars $compileCp (Join-Path $gameDir ($versionId + ".json"))
$otherJson = Get-ChildItem -Recurse -File -Path $versionsRoot -Filter *.json |
    Where-Object { $_.FullName -match 'NeoForge' } | Select-Object -First 1
if ($otherJson) {
    Add-VersionJars $compileCp $otherJson.FullName
    $otherDir = Split-Path $otherJson.FullName -Parent
    $otherJar = Get-ChildItem -File -Path $otherDir -Filter *.jar | Select-Object -First 1
    if ($otherJar) { [void]$compileCp.Add($otherJar.FullName) }
}
foreach ($j in @((Find-NewestJar "neoforge-*-universal.jar"), (Find-NewestJar "loader-12.*.jar"),
                 (Find-NewestJar "bus-8.*.jar"), (Find-NewestJar "typetools-*.jar"),
                 (Find-NewestJar "maven-artifact-*.jar"))) {
    if ($j) { [void]$compileCp.Add($j) }
}
$compileCp = ($compileCp | Select-Object -Unique) -join ";"
Write-Host "[probe] compile classpath entries: $($compileCp.Split(';').Count)"

$out = Join-Path $root "build\crossdev"
$classes = Join-Path $out "classes"
$stage = Join-Path $out "stage"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $stage | Out-Null

Write-Host "[probe] compiling fixture..."
$sources = Get-ChildItem -Recurse -Path (Join-Path $PSScriptRoot "src") -Filter *.java |
    ForEach-Object { $_.FullName }
& $javac -nowarn --release 17 -cp $compileCp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "fixture compilation failed" }

Copy-Item -Recurse -Force (Join-Path $PSScriptRoot "res\*") $stage
Copy-Item -Recurse -Force (Join-Path $classes "*") $stage

$jarPath = Join-Path $out "fusionbridgeprobe-1.0.0.jar"
& $jartool --create --file $jarPath -C $stage .
if ($LASTEXITCODE -ne 0) { throw "fixture packaging failed" }

New-Item -ItemType Directory -Force -Path $ModsDir | Out-Null
Copy-Item -Force $jarPath (Join-Path $ModsDir "fusionbridgeprobe-1.0.0.jar")
Write-Host "[probe] installed to $ModsDir"
