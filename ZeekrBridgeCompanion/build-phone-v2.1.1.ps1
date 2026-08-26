$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidStudioJdk = "C:\Program Files\Android\Android Studio\jbr"
$androidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"

if (-not (Test-Path -LiteralPath (Join-Path $androidStudioJdk "bin\java.exe"))) {
    throw "Android Studio JDK not found: $androidStudioJdk"
}
if (-not (Test-Path -LiteralPath $androidSdk)) {
    throw "Android SDK not found: $androidSdk"
}

$env:JAVA_HOME = $androidStudioJdk
$env:ANDROID_HOME = $androidSdk
$env:Path = "$androidStudioJdk\bin;$env:Path"

Push-Location $projectRoot
try {
    # Windows Lint workers can briefly retain their cache after a previous build.
    & ".\gradlew.bat" --stop | Out-Host
    & ".\gradlew.bat" clean testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Phone build failed with exit code $LASTEXITCODE" }

    $sourceApk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
    $artifactDir = Join-Path $projectRoot "release-artifacts"
    $artifactApk = Join-Path $artifactDir "OpenAVM-Companion-v2.1.1-private-update.apk"
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    Copy-Item -LiteralPath $sourceApk -Destination $artifactApk -Force

    $signer = Join-Path $androidSdk "build-tools\36.0.0\apksigner.bat"
    & $signer verify --print-certs $artifactApk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }

    Write-Host "READY: $artifactApk"
} finally {
    Pop-Location
}
