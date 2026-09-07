# Builds the Blockin game and stages classes + resources into the play
# directory. Run from the Blockin Source root.
$ErrorActionPreference = "Stop"

$root   = $PSScriptRoot
$game   = "D:\Games\Blockin"
$cp     = "lib\lwjgl.jar;lib\lwjgl_util.jar"
$srcs   = Join-Path $root "sources.txt"

# Recursively merges a source tree into a destination tree
# (stages contents, not the folder itself).
function Copy-Merge {
    param([string]$src, [string]$dst)
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    foreach ($dir in Get-ChildItem $src -Directory) {
        Copy-Merge $dir.FullName (Join-Path $dst $dir.Name)
    }
    foreach ($file in Get-ChildItem $src -File) {
        Copy-Item -Force $file.FullName (Join-Path $dst $file.Name)
    }
}

# 0) Clean old compiled .class files from the play directory
Write-Host "=== Cleaning old .class files ==="
if (Test-Path $game) {
    Get-ChildItem -Path $game -Recurse -Filter *.class | Remove-Item -Force
}

# 1) Compile all Java sources straight into the play dir (runtime classpath base)
Write-Host "=== Compiling Blockin (javac) ==="
if (Test-Path $srcs) { Remove-Item $srcs -Force }
$files = Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $root "com")
$files.FullName | ForEach-Object { $_.Replace("$root\", "").Replace("\", "/") } | Set-Content $srcs
New-Item -ItemType Directory -Force -Path $game | Out-Null
cmd /c "javac -encoding UTF-8 -cp `"$cp`" -d `"$game`" @`"$srcs`"" | Out-String -Width 400
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# 2) Stage resources (com/**/*.png, *.wav) into the class tree
Write-Host "=== Staging resources ==="
foreach ($res in Get-ChildItem (Join-Path $root "com") -Recurse -Include *.png,*.wav) {
    $rel = $res.FullName.Substring($root.Length + 1)
    $dest = Join-Path $game $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item -Force $res.FullName $dest
}

# 3) Stage Mods > game root (working-dir mods folder), merging into any
# existing Mods tree (never nesting into itself)
$srcMods = Join-Path $root "Mods"
$dstMods = Join-Path $game "Mods"
if (Test-Path $srcMods) {
    Write-Host "=== Staging mods ==="
    Copy-Merge $srcMods $dstMods
}

# 4) Natives (only if missing)
if (-not (Test-Path (Join-Path $game "natives"))) {
    Write-Host "=== Staging natives ==="
    Copy-Item -Recurse -Force (Join-Path $root "natives") (Join-Path $game "natives")
}

Write-Host "[BUILD OK] classes and resources deployed to $game"
Read-Host -Prompt "Press Enter to exit"