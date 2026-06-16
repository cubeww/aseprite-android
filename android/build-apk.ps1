param(
  [string] $Abi = "arm64-v8a",
  [string] $NativeApi = "android-23",
  [string] $CompileSdk = "android-35",
  [string] $BuildTools = "35.0.0",
  [string] $NdkVersion = "27.2.12479018",
  [string] $SdkRoot = $env:ANDROID_SDK_ROOT,
  [ValidateSet("none", "skia")]
  [string] $LafBackend = "skia",
  [string] $SkiaDir = "",
  [string] $SkiaLibraryDir = "",
  [string] $SkiaDownloadUrl = "https://github.com/cubeww/aseprite-android/releases/download/android-skia-arm64-v1/skia-android-arm64.zip",
  [string] $SkiaArchiveSha256 = "fd3d2763c803e13c2ea5992f693ecc3440a559fe71d39fd92939f298f3792a35",
  [switch] $NoSkiaDownload,
  [switch] $Unsigned
)

$ErrorActionPreference = "Stop"

function Require-File($Path, $Name) {
  if (!(Test-Path -LiteralPath $Path)) {
    throw "$Name not found: $Path"
  }
}

function Invoke-Step($File, [string[]] $Arguments) {
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$File failed with exit code $LASTEXITCODE"
  }
}

function Assert-UnderDirectory($Path, $Root, $Name) {
  $fullPath = [System.IO.Path]::GetFullPath($Path)
  $fullRoot = [System.IO.Path]::GetFullPath($Root)
  $rootPrefix = $fullRoot.TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

  if (!$fullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "$Name resolved outside expected directory: $fullPath"
  }
}

function Ensure-SkiaPrebuilt($PrebuiltDir, $LibraryDir, $BuildRoot, $DownloadUrl, $ExpectedSha256) {
  $skiaLib = Join-Path $LibraryDir "libskia.a"
  if (Test-Path -LiteralPath $skiaLib) {
    return
  }

  if ($NoSkiaDownload) {
    throw "Bundled Skia prebuilt was not found and -NoSkiaDownload was specified: $PrebuiltDir"
  }

  if (!$DownloadUrl) {
    throw "Bundled Skia prebuilt was not found and SkiaDownloadUrl is empty"
  }

  New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
  $archivePath = Join-Path $BuildRoot "skia-android-arm64.zip"
  $extractRoot = Join-Path $BuildRoot "skia-android-arm64-extract"
  Assert-UnderDirectory $archivePath $BuildRoot "Skia archive path"
  Assert-UnderDirectory $extractRoot $BuildRoot "Skia extract path"

  Write-Host "Downloading Skia prebuilt: $DownloadUrl"
  [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -Uri $DownloadUrl -OutFile $archivePath

  if ($ExpectedSha256) {
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
      Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
      throw "Skia archive SHA256 mismatch. Expected $ExpectedSha256, got $actualSha256"
    }
  }

  Remove-Item -LiteralPath $extractRoot -Recurse -Force -ErrorAction SilentlyContinue
  Expand-Archive -LiteralPath $archivePath -DestinationPath $extractRoot -Force

  $extractedDir = Join-Path $extractRoot "skia-android-arm64"
  Require-File (Join-Path $extractedDir "out/Release-arm64/libskia.a") "Downloaded Skia prebuilt"

  $prebuiltParent = Split-Path -Parent $PrebuiltDir
  New-Item -ItemType Directory -Force -Path $prebuiltParent | Out-Null
  Assert-UnderDirectory $PrebuiltDir $prebuiltParent "Skia prebuilt path"
  Remove-Item -LiteralPath $PrebuiltDir -Recurse -Force -ErrorAction SilentlyContinue
  Move-Item -LiteralPath $extractedDir -Destination $PrebuiltDir
  Remove-Item -LiteralPath $extractRoot -Recurse -Force -ErrorAction SilentlyContinue
}

function Find-Keytool() {
  $cmd = Get-Command keytool -ErrorAction SilentlyContinue
  if ($cmd) {
    return $cmd.Source
  }

  $javaHome = $env:JAVA_HOME
  if (!$javaHome) {
    $settings = & java -XshowSettings:properties -version 2>&1
    $line = $settings | Select-String -Pattern "^\s*java.home\s*="
    if ($line) {
      $javaHome = ($line.ToString() -split "=", 2)[1].Trim()
    }
  }

  if ($javaHome) {
    $candidate = Join-Path $javaHome "bin/keytool.exe"
    if (Test-Path -LiteralPath $candidate) {
      return $candidate
    }
  }

  return $null
}

if (!$SdkRoot) {
  throw "ANDROID_SDK_ROOT is not set"
}

if ($SkiaDir -and $LafBackend -eq "none") {
  $LafBackend = "skia"
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptRoot
$appRoot = Join-Path $scriptRoot "app"
$buildRoot = Join-Path $scriptRoot "build"
$prebuiltSkiaDir = Join-Path $scriptRoot "prebuilt/skia-android-arm64"
$prebuiltSkiaLibraryDir = Join-Path $prebuiltSkiaDir "out/Release-arm64"
$hostBuildDir = Join-Path $repoRoot "build-host-gen"
$nativeBuildDir = Join-Path $buildRoot "cmake-$Abi-$LafBackend"
$hostGen = Join-Path $hostBuildDir "bin/gen.exe"
$androidJar = Join-Path $SdkRoot "platforms/$CompileSdk/android.jar"
$ndkToolchain = Join-Path $SdkRoot "ndk/$NdkVersion/build/cmake/android.toolchain.cmake"
$buildToolsDir = Join-Path $SdkRoot "build-tools/$BuildTools"
$aapt2 = Join-Path $buildToolsDir "aapt2.exe"
$d8 = Join-Path $buildToolsDir "d8.bat"
$zipalign = Join-Path $buildToolsDir "zipalign.exe"
$apksigner = Join-Path $buildToolsDir "apksigner.bat"

if ($LafBackend -eq "skia" -and !$SkiaDir) {
  if ($Abi -ne "arm64-v8a") {
    throw "SkiaDir is required for $Abi because the downloadable Skia prebuilt only supports arm64-v8a"
  }

  Ensure-SkiaPrebuilt $prebuiltSkiaDir $prebuiltSkiaLibraryDir $buildRoot $SkiaDownloadUrl $SkiaArchiveSha256
  $SkiaDir = $prebuiltSkiaDir
  if (!$SkiaLibraryDir) {
    $SkiaLibraryDir = $prebuiltSkiaLibraryDir
  }
}

Require-File $androidJar "Android platform jar"
Require-File $ndkToolchain "Android NDK CMake toolchain"
Require-File $aapt2 "aapt2"
Require-File $d8 "d8"
Require-File $zipalign "zipalign"

$hostCMakeArgs = @(
  "-S", $repoRoot,
  "-B", $hostBuildDir,
  "-G", "Ninja",
  "-DLAF_BACKEND=none",
  "-DENABLE_ASEPRITE_EXE=OFF",
  "-DENABLE_NEWS=OFF",
  "-DENABLE_UPDATER=OFF",
  "-DENABLE_SCRIPTING=OFF",
  "-DENABLE_WEBSOCKET=OFF",
  "-DENABLE_WEBP=OFF",
  "-DENABLE_DESKTOP_INTEGRATION=OFF",
  "-DENABLE_TESTS=OFF",
  "-DLAF_WITH_EXAMPLES=OFF")

if (!(Test-Path -LiteralPath $hostGen)) {
  Invoke-Step "cmake" $hostCMakeArgs
  Invoke-Step "cmake" @("--build", $hostBuildDir, "--target", "gen")
}

$nativeCMakeArgs = @(
  "-USKIA_LIBRARY",
  "-USKUNICODE_LIBRARY",
  "-USKSHAPER_LIBRARY",
  "-UFREETYPE_LIBRARY",
  "-UHARFBUZZ_LIBRARY",
  "-ULIBJPEG_TURBO_LIBRARY",
  "-S", $repoRoot,
  "-B", $nativeBuildDir,
  "-G", "Ninja",
  "-DCMAKE_TOOLCHAIN_FILE=$ndkToolchain",
  "-DANDROID_ABI=$Abi",
  "-DANDROID_PLATFORM=$NativeApi",
  "-DGEN_EXE=$hostGen",
  "-DLAF_BACKEND=$LafBackend",
  "-DENABLE_ASEPRITE_EXE=OFF",
  "-DENABLE_NEWS=OFF",
  "-DENABLE_UPDATER=OFF",
  "-DENABLE_SCRIPTING=OFF",
  "-DENABLE_WEBSOCKET=OFF",
  "-DENABLE_WEBP=OFF",
  "-DENABLE_DESKTOP_INTEGRATION=OFF",
  "-DENABLE_TESTS=OFF",
  "-DLAF_WITH_EXAMPLES=OFF")

if ($LafBackend -eq "skia") {
  if (!$SkiaDir) {
    throw "SkiaDir is required when LafBackend=skia"
  }
  $nativeCMakeArgs += "-DSKIA_DIR=$SkiaDir"
  if ($SkiaLibraryDir) {
    $nativeCMakeArgs += "-DSKIA_LIBRARY_DIR=$SkiaLibraryDir"
  }
}

Invoke-Step "cmake" $nativeCMakeArgs
Invoke-Step "cmake" @("--build", $nativeBuildDir, "--target", "aseprite-android")

$nativeLib = Join-Path $nativeBuildDir "lib/libaseprite-android.so"
Require-File $nativeLib "Android native library"

$resZip = Join-Path $buildRoot "compiled-res.zip"
$generatedJava = Join-Path $buildRoot "generated"
$classesDir = Join-Path $buildRoot "classes"
$dexDir = Join-Path $buildRoot "dex"
$unsignedApk = Join-Path $buildRoot "aseprite-android-unsigned.apk"
$alignedApk = Join-Path $buildRoot "aseprite-android-aligned-unsigned.apk"
$signedApk = Join-Path $buildRoot "aseprite-android-debug.apk"

New-Item -ItemType Directory -Force -Path $buildRoot, $generatedJava, $classesDir, $dexDir | Out-Null
Remove-Item -LiteralPath $resZip, $unsignedApk, $alignedApk, $signedApk -Force -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath $classesDir -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
Get-ChildItem -LiteralPath $dexDir -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force

Invoke-Step $aapt2 @("compile", "--dir", (Join-Path $appRoot "src/main/res"), "-o", $resZip)
Invoke-Step $aapt2 @(
  "link",
  "-o", $unsignedApk,
  "-I", $androidJar,
  "--manifest", (Join-Path $appRoot "src/main/AndroidManifest.xml"),
  "--java", $generatedJava,
  $resZip)

$sourceList = Join-Path $buildRoot "java-sources.txt"
Get-ChildItem -Path (Join-Path $appRoot "src/main/java"), $generatedJava -Recurse -Filter "*.java" |
  ForEach-Object { $_.FullName } |
  Set-Content -LiteralPath $sourceList -Encoding ASCII

Invoke-Step "javac" @(
  "-encoding", "UTF-8",
  "-source", "8",
  "-target", "8",
  "-classpath", $androidJar,
  "-d", $classesDir,
  "@$sourceList")

$classFiles = Get-ChildItem -LiteralPath $classesDir -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
Invoke-Step $d8 (@("--min-api", "23", "--classpath", $androidJar, "--output", $dexDir) + $classFiles)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  foreach ($entryName in @("classes.dex", "lib/$Abi/libaseprite-android.so")) {
    $existing = $zip.GetEntry($entryName)
    if ($existing) {
      $existing.Delete()
    }
  }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip,
    (Join-Path $dexDir "classes.dex"),
    "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip,
    $nativeLib,
    "lib/$Abi/libaseprite-android.so",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

  $dataRoot = Join-Path $repoRoot "data"
  $dataRootFull = (Resolve-Path -LiteralPath $dataRoot).Path
  Get-ChildItem -LiteralPath $dataRootFull -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($dataRootFull.Length)
    $relative = $relative.TrimStart(
      [System.IO.Path]::DirectorySeparatorChar,
      [System.IO.Path]::AltDirectorySeparatorChar)
    $entryName = "assets/data/" + ($relative -replace "\\", "/")
    $existing = $zip.GetEntry($entryName)
    if ($existing) {
      $existing.Delete()
    }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
      $zip,
      $_.FullName,
      $entryName,
      [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
}
finally {
  $zip.Dispose()
}

Invoke-Step $zipalign @("-f", "-p", "4", $unsignedApk, $alignedApk)

if (!$Unsigned) {
  Require-File $apksigner "apksigner"
  $keytool = Find-Keytool
  if (!$keytool) {
    Write-Warning "keytool was not found; leaving unsigned APK at $alignedApk"
    return
  }

  $debugKeystore = Join-Path $scriptRoot "debug.keystore"
  if (!(Test-Path -LiteralPath $debugKeystore)) {
    Invoke-Step $keytool @(
      "-genkeypair",
      "-keystore", $debugKeystore,
      "-storepass", "android",
      "-keypass", "android",
      "-alias", "androiddebugkey",
      "-keyalg", "RSA",
      "-keysize", "2048",
      "-validity", "10000",
      "-dname", "CN=Android Debug,O=Android,C=US")
  }

  Invoke-Step $apksigner @(
    "sign",
    "--ks", $debugKeystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $signedApk,
    $alignedApk)
  Invoke-Step $apksigner @("verify", "--verbose", $signedApk)
  Write-Host "APK: $signedApk"
}
else {
  Write-Host "APK: $alignedApk"
}
