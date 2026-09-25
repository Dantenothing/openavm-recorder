# OpenAVM Phone V5

> **Phone download temporarily paused:** The V5 phone APK has been withdrawn while we review distribution of its bundled third-party connection configuration. Vehicle Recorder remains available. A replacement phone package is not available yet. Phone instructions below apply to existing V5 installations.

This is the current Android phone application: vehicle status and controls, preconditioning, factory Sentry rules, home-screen widgets, and the OpenAVM recording library.

Source candidate: **5.0.1 / 52**. The normal `openavm` variant uses `com.dante.zeekrbridge`, preserving the original OpenAVM phone upgrade identity. The separate `assistantUpgrade` variant is only for the early standalone assistant and is not a normal public download.

[Optional cloud setup](../docs/v5/CLOUD_SETUP.md) · [User setup](../docs/v5/GETTING_STARTED.md) · [Build notes](../docs/v5/BUILD.md)

## Source layout

- `app/`: unified navigation, vehicle controls, widgets and automation.
- `openavm-companion/`: library wrapper around `../ZeekrBridgeCompanion/app/src/main`, including playback and secure local transfer.
- `../transfer-protocol`, `../sound-core`, `../localization`: shared modules.

Cloud vehicle login and Recorder pairing are independent. Normal refresh only reads status; the separate temperature action may briefly run climate control. No private server is required.

## Building

Use JDK 17 or newer compatible with the checked-in Gradle wrapper and Android SDK 36. Set your local SDK path in `local.properties`.

Public builds do not read or bundle manufacturer connection parameters. No private JSON or zeekrProtocolFile property is required. Users can use local recordings immediately; optional cloud access requires an independently prepared configuration imported in the app, followed by account login. See the cloud setup guide and empty template.

From this directory:

```text
gradlew.bat :app:assembleOpenavmRelease
gradlew.bat :app:testOpenavmReleaseUnitTest :openavm-companion:testReleaseUnitTest :app:lintOpenavmRelease
```

Release builds are not debuggable. The local signing configuration preserves the established maintainer upgrade channel on the maintainer's machine; building with another machine's key does not create an official upgrade.

Tests under `androidTest` use separate QA variants where appropriate. Do not run tests that clear application data against a configured personal installation.
