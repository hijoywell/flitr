#!/usr/bin/env pwsh
#
# 本地构建入口。
#
# 为什么需要这个脚本，而不是直接 ./gradlew：
#
#   Gradle 默认把 user home 放在 ~/.gradle、把临时文件放在 %TEMP%。
#   在「只允许写工作区」的沙箱里（比如本项目的开发环境），这两处都写不进去，
#   Gradle 会在加载原生库时直接失败，报：
#
#       Could not initialize native services.
#       > Failed to load native library 'native-platform.dll' for Windows 11 amd64.
#
#   这跟 Gradle 或 JDK 是否损坏无关，纯粹是路径没写权限。
#   所以这里把两者都指到仓库内的 .tooling/ 下。
#
# 用法：
#   ./build.ps1                  # 构建 release APK
#   ./build.ps1 :app:testDebugUnitTest
#   ./build.ps1 :app:assembleRelease -Clean

param(
    [string]$Task = ":app:assembleRelease",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot

# JDK / Android SDK 优先用环境变量，其次用 .tooling/ 里的副本
if (-not $env:JAVA_HOME) {
    $candidate = Join-Path $repo ".tooling\jdk21"
    if (Test-Path $candidate) {
        $env:JAVA_HOME = $candidate
        Write-Host "[build] JAVA_HOME = $candidate"
    }
}
if (-not $env:ANDROID_HOME) {
    $candidate = Join-Path $repo ".tooling\android-sdk"
    if (Test-Path $candidate) {
        $env:ANDROID_HOME = $candidate
        $env:ANDROID_SDK_ROOT = $candidate
        Write-Host "[build] ANDROID_HOME = $candidate"
    }
}

# 关键：把 Gradle 的写入位置全部限制在仓库内
$gradleHome = Join-Path $repo ".tooling\gradle-home"
$tmpDir = Join-Path $repo ".tooling\jtmp"
New-Item -ItemType Directory -Force -Path $gradleHome, $tmpDir | Out-Null

$env:GRADLE_USER_HOME = $gradleHome
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$tmpDir"

# 优先用本地解压的 Gradle，避免 gradlew 去 ~/.gradle/wrapper 找发行版
$gradle = Join-Path $repo ".tooling\gradle-9.9.1\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    $gradle = Join-Path $repo "gradlew.bat"
}

if ($Clean) {
    & $gradle clean --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

& $gradle $Task --console=plain
exit $LASTEXITCODE
