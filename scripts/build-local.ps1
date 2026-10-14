$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$buildDirectory = $projectDirectory
if ($projectDirectory -match '[^\x00-\x7F]') {
    if ($env:TEMP -match '[^\x00-\x7F]') {
        throw 'JDK 17 在 Windows 中文路径下无法可靠加载测试类，请通过英文目录的 Junction 打开工程。'
    }
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $projectHash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectDirectory))).Replace('-', '').Substring(0, 12)
    } finally { $hasher.Dispose() }
    $buildDirectory = Join-Path $env:TEMP "qingxu-build-$projectHash"
    if (Test-Path -LiteralPath $buildDirectory) {
        $existingAlias = Get-Item -LiteralPath $buildDirectory
        if ($existingAlias.LinkType -ne 'Junction' -or $existingAlias.Target -ne $projectDirectory) {
            throw "构建路径已被其他目录占用：$buildDirectory"
        }
    } else {
        New-Item -ItemType Junction -Path $buildDirectory -Target $projectDirectory | Out-Null
    }
    Write-Host "使用英文路径构建：$buildDirectory"
}
$jdkDirectory = Get-ChildItem -LiteralPath "$buildDirectory\.tools\jdk" -Directory | Select-Object -First 1
if (-not $jdkDirectory) { throw '未找到 .tools/jdk，请配置 JDK 17 或在 Android Studio 中构建。' }
$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$previousPath = $env:PATH
$env:JAVA_HOME = $jdkDirectory.FullName
$env:ANDROID_HOME = "$buildDirectory\.tools\android-sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Push-Location -LiteralPath $buildDirectory
try {
    $localGradle = Join-Path $buildDirectory '.tools\gradle\gradle-8.9\bin\gradle.bat'
    $gradleRunner = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { '.\gradlew.bat' }
    & $gradleRunner :app:assembleDebug :app:testDebugUnitTest :app:lintDebug @args
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败：$LASTEXITCODE" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    $env:PATH = $previousPath
}
