param(
    [Parameter(Mandatory)] [string] $MorpheDesktop,
    [Parameter(Mandatory)] [string] $GradleLib,
    [string] $AndroidSdk = $env:ANDROID_HOME,
    [string] $Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (!$AndroidSdk -or !$Jdk) {
    throw 'Set ANDROID_HOME and JAVA_HOME, or pass -AndroidSdk and -Jdk.'
}

$desktop = (Resolve-Path -LiteralPath $MorpheDesktop).Path
$gradle = (Resolve-Path -LiteralPath $GradleLib).Path
$stdlib = (Get-ChildItem -LiteralPath $gradle -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1).FullName
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$patches = Join-Path $repository 'patches'
$androidJar = Join-Path $AndroidSdk 'platforms/android-33/android.jar'
$d8Jar = Join-Path $AndroidSdk 'build-tools/36.1.0/lib/d8.jar'

function Invoke-BuildStep {
    param([string] $Executable, [string[]] $Arguments)
    & $Executable @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path $Executable -Leaf) exited with code $LASTEXITCODE."
    }
}

foreach ($required in @($androidJar, $d8Jar, $stdlib)) {
    if (!$required -or !(Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing build dependency $required"
    }
}

$runId = [Guid]::NewGuid().ToString('N')
$build = (New-Item -ItemType Directory -Force -Path (Join-Path $patches "build/monitor-local/$runId")).FullName
$extension = (New-Item -ItemType Directory (Join-Path $build 'extension')).FullName
$dex = (New-Item -ItemType Directory (Join-Path $build 'dex')).FullName
$classes = (New-Item -ItemType Directory (Join-Path $build 'classes')).FullName
$javaSources = @(Get-ChildItem (Join-Path $patches 'src/monitorExtension/java') -Recurse -Filter '*.java' |
    ForEach-Object FullName)
Invoke-BuildStep (Join-Path $Jdk 'bin/javac.exe') (@(
    '--release', '11', '-encoding', 'UTF-8', '-classpath', $androidJar, '-d', $extension
) + $javaSources)

$extensionClasses = @(Get-ChildItem $extension -Recurse -Filter '*.class' | ForEach-Object FullName)
Invoke-BuildStep (Join-Path $Jdk 'bin/java.exe') (@(
    '-cp', $d8Jar, 'com.android.tools.r8.D8', '--min-api', '33', '--lib', $androidJar, '--output', $dex
) + $extensionClasses)
Copy-Item -LiteralPath (Join-Path $dex 'classes.dex') -Destination (Join-Path $classes 'remote-monitor.mpe')

$kotlinSources = @(Get-ChildItem (Join-Path $patches 'src/main/kotlin/app') -Recurse -Filter '*.kt' |
    ForEach-Object FullName)
Invoke-BuildStep (Join-Path $Jdk 'bin/java.exe') (@(
    '-cp', (Join-Path $gradle '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', "$desktop;$stdlib", '-d', $classes
) + $kotlinSources)

$patchDex = (New-Item -ItemType Directory (Join-Path $build 'patch-dex')).FullName
$patchClasses = @(Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
Invoke-BuildStep (Join-Path $Jdk 'bin/java.exe') (@(
    '-cp', $d8Jar, 'com.android.tools.r8.D8', '--release', '--min-api', '26',
    '--lib', $androidJar, '--classpath', $desktop, '--output', $patchDex
) + $patchClasses)
Get-ChildItem $patchDex -Filter 'classes*.dex' | Copy-Item -Destination $classes

$bundle = Join-Path $build 'blackmagic-monitor-development.mpp'
Invoke-BuildStep (Join-Path $Jdk 'bin/jar.exe') @(
    "-J-Djava.io.tmpdir=$build", '--create', '--file', $bundle, '-C', $classes, '.'
)
$bundle
