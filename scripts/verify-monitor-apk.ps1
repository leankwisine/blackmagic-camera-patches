param(
    [Parameter(Mandatory)] [string] $StockBaseApk,
    [Parameter(Mandatory)] [string] $PatchBundle,
    [Parameter(Mandatory)] [string] $MorpheDesktop,
    [Parameter(Mandatory)] [string] $Apktool,
    [string] $Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (!$Jdk) { throw 'Set JAVA_HOME or pass -Jdk.' }
$java = Join-Path $Jdk 'bin/java.exe'
$stock = (Resolve-Path -LiteralPath $StockBaseApk).Path
$bundle = (Resolve-Path -LiteralPath $PatchBundle).Path
$desktop = (Resolve-Path -LiteralPath $MorpheDesktop).Path
$apktoolJar = (Resolve-Path -LiteralPath $Apktool).Path
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runId = [Guid]::NewGuid().ToString('N')
$verify = (New-Item -ItemType Directory -Force -Path (
    Join-Path $repository "patches/build/monitor-verification/$runId"
)).FullName
$patched = Join-Path $verify 'base-monitor-unsigned.apk'
$report = Join-Path $verify 'patch-result.json'
$stockSmali = Join-Path $verify 'stock'
$patchedSmali = Join-Path $verify 'patched'
$stockHash = (Get-FileHash -LiteralPath $stock).Hash
$runtime = 'Lapp/blackmagic/patches/monitor/RemotePreview;'

function Invoke-Jar {
    param([string] $Jar, [string[]] $Arguments)
    & $java "-Djava.io.tmpdir=$verify" -jar $Jar @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $Jar -Leaf) exited with code $LASTEXITCODE." }
}

function Get-Smali {
    param([string] $Tree, [string] $Class)
    $paths = @(Get-ChildItem -LiteralPath $Tree -Directory -Filter 'smali*' | ForEach-Object {
        $path = Join-Path $_.FullName "$Class.smali"
        if (Test-Path -LiteralPath $path -PathType Leaf) { $path }
    })
    if ($paths.Count -ne 1) { throw "Expected one definition of $Class in $Tree." }
    Get-Content -LiteralPath $paths[0] -Raw
}

Invoke-Jar $desktop @(
    'patch', '--exclusive', '--validate', '--unsigned', '--bytecode-mode=FULL',
    '-p', $bundle, '-e', 'Remote monitor display transforms', '-o', $patched, '-r', $report, $stock
)
Invoke-Jar $apktoolJar @('d', '-r', '-o', $stockSmali, $stock)
Invoke-Jar $apktoolJar @('d', '-r', '-o', $patchedSmali, $patched)
$callback = Get-Smali $patchedSmali 'd35'
$renderer = Get-Smali $patchedSmali 'kb2'
foreach ($method in @('prepare(', 'bind(', 'unbind(')) {
    if (!$callback.Contains("$runtime->$method")) { throw "Missing surface hook $method" }
}
foreach ($method in @('begin(', 'draw(', 'lutForOutput(')) {
    if (!$renderer.Contains("$runtime->$method")) { throw "Missing presentation hook $method" }
}
foreach ($class in @('gz7', 'jk')) {
    if (!(Get-Smali $patchedSmali $class).Contains("$runtime->register(")) {
        throw "Missing selected controller surface hook in $class."
    }
}
$focus = Get-Smali $patchedSmali 't8a'
$tap = Get-Smali $patchedSmali 'di1'
foreach ($method in @('focusToStock(', 'focusToDisplay(')) {
    if (!$focus.Contains("$runtime->$method")) { throw "Missing remote focus hook $method" }
}
if (!$tap.Contains("$runtime->keepVideoTap(")) { throw 'Missing preview tap bounds hook.' }
if (!(Get-Smali $patchedSmali 'zi9').Contains("$runtime->setDecodedFormat(")) {
    throw 'Missing remote stream aspect hooks.'
}
$null = Get-Smali $patchedSmali 'app/blackmagic/patches/monitor/RemotePreview'
$lut = Get-Smali $patchedSmali 'app/blackmagic/patches/monitor/MonitorLut'
if (!$lut.Contains('Float16LutParser;->a(Landroid/content/Context;Landroid/net/Uri;)')) {
    throw 'Missing native LUT parser bridge.'
}
$null = Get-Smali $patchedSmali 'app/blackmagic/patches/monitor/MonitorLut$Picker'

foreach ($class in @(
    'cj9', 'xga', 'pea', 'a8a', 'ej9',
    'com/blackmagicdesign/android/remote/jni/NativeGstreamer',
    'com/pairip/licensecheck/LicenseClient'
)) {
    if ((Get-Smali $stockSmali $class) -cne (Get-Smali $patchedSmali $class)) {
        throw "Unexpected smali change in $class."
    }
}
if ((Get-FileHash -LiteralPath $stock).Hash -cne $stockHash) {
    throw 'The original base APK changed.'
}
"Verified monitor hooks, extension DEX, and unchanged decoder setup, transport, and license classes. Report: $report"
