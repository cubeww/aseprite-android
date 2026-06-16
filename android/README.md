# Aseprite Android build

This Android port builds an `arm64-v8a` debug APK with the Skia backend by
default.

## Prerequisites

- Android SDK platform `android-35`
- Android build tools `35.0.0`
- Android NDK `27.2.12479018`
- CMake, Ninja, Java `javac`, and `keytool`
- `ANDROID_SDK_ROOT` set to the Android SDK path

## Build

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1
```

The APK is written to:

```text
android\build\aseprite-android-debug.apk
```

The script uses the bundled `arm64-v8a` Skia prebuilt package in
`android/prebuilt/skia-android-arm64`. If that directory is missing, the script
downloads the package from the GitHub Release asset, verifies its SHA256, and
extracts it into `android/prebuilt/skia-android-arm64`.

Release asset used by default:

```text
https://github.com/cubeww/aseprite/releases/download/android-skia-arm64-v1/skia-android-arm64.zip
```

Expected SHA256:

```text
fd3d2763c803e13c2ea5992f693ecc3440a559fe71d39fd92939f298f3792a35
```

To require an already-present local prebuilt package and avoid network access:

```powershell
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 -NoSkiaDownload
```

To use another Skia build:

```powershell
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 `
  -LafBackend skia `
  -SkiaDir C:\deps\skia `
  -SkiaLibraryDir C:\deps\skia\out\Release-arm64
```

Only the bundled `arm64-v8a` prebuilt is provided. Other ABIs require a matching
Skia build passed through `-SkiaDir` and `-SkiaLibraryDir`.
