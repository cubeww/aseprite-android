# Android prebuilts

This directory is used for downloaded Android native prebuilts.

The default Android build script downloads the `arm64-v8a` Skia package from:

```text
https://github.com/cubeww/aseprite/releases/download/android-skia-arm64-v1/skia-android-arm64.zip
```

It verifies the archive with SHA256:

```text
fd3d2763c803e13c2ea5992f693ecc3440a559fe71d39fd92939f298f3792a35
```

After extraction, the local layout is:

```text
android/prebuilt/skia-android-arm64/
```

That extracted directory is intentionally ignored by Git. Keep the Release asset
as the source of truth for this binary package.
