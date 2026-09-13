# Building and verifying V4

[Home](README.md) · [Release notes](GITHUB_RELEASE_V4.md)

## Projects

- Root `app`: vehicle Recorder, application ID `io.github.dantenothing.openavmrecorder`.
- `ZeekrBridgeCompanion/app`: optional Android phone app, application ID `com.dante.zeekrbridge`.
- `sound-core`, `localization`, `transfer-protocol`: shared modules; keep the repository layout intact.
- Root `phone`: retained legacy module, not the V4 Companion download.

Use JDK 17 or a compatible newer JDK, Android SDK platform 36 and the checked-in Gradle 8.13 wrapper. Configure your own SDK path through Android Studio, `ANDROID_HOME` or an untracked `local.properties`. No signing key or machine-local SDK configuration is published.

## Verify/build the vehicle and shared modules

```sh
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :sound-core:testReleaseUnitTest :localization:testReleaseUnitTest :transfer-protocol:testReleaseUnitTest --no-daemon --max-workers=1
```

## Verify/build the phone

```sh
cd ZeekrBridgeCompanion
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --no-daemon --max-workers=1
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`. Run the two builds sequentially on memory-constrained computers. The release verification used one worker, a 1536 MB Gradle heap, Kotlin compilation in-process and filesystem watching disabled. The vehicle release keeps ARM64 only; the phone keeps ARM64, ARMv7, x86 and x86_64.

Each project produces `app/build/outputs/apk/release/app-release.apk`. Release builds disable debugging and enable R8/resource shrinking. The current signing configuration is named `debug` to preserve compatibility with earlier developer-signed installations. Your locally generated key will differ from the official release key, so your APK cannot update an existing official installation. The configuration name does not make the release APK debuggable.

## Release provenance and validation

The published APKs are the frozen V4 deliverables already prepared before source publication. They were not replaced by the public-source rebuild. Runtime source and resources copied for publication were checked against the frozen V4 source manifest. The vehicle APK's diagnostic Git marker reflects the pre-publication build checkout (`d01d90f`); it is not the later publication commit. Use the release tag plus the APK SHA-256 values to identify this release.

Before publication, the APKs were rechecked for package/version identity, signature continuity, zip alignment/integrity, release debugging state, architectures, six packaged translation catalogs, matching launcher artwork and absence of the retired parking AI model/OpenCV. Fresh public-source build results are recorded in `RELEASE_VERIFICATION.json`.

The owner retested the RC2 vehicle-away extra-file fix and reported no recurrence, and accepted the phone UI. V4 retains those runtime implementations; final branding and Settings visibility changes received local verification. Automated checks do not establish camera ownership, power-down or USB reliability on every vehicle.

Full private camera/sound fixtures, vehicle captures, field diagnostics, model binaries and recovery ZIPs are intentionally not published. The public release checks are JVM tests, lint and release builds; they do not claim the omitted private Android instrumentation fixtures can be run from this checkout.

## Paused research

Some shared release protections and historical-media readers retain names containing `Sentry` or `Canary`. Those names do not mean custom parking capture is enabled. All parking capture build flags are false; no Guard/Canary camera service is registered in the V4 manifest. R8 removes unreachable experimental capture code from the release APK. See [the reference index](research/sentry/README_V4_ARCHIVE.md).
