# OpenAVM Phone V5

This is the current Android phone application: vehicle status and controls, preconditioning, factory Sentry rules, home-screen widgets, and the OpenAVM recording library.

Version: **5.0.0 / 51**. The normal `openavm` variant uses `com.dante.zeekrbridge`, preserving the original OpenAVM phone upgrade identity. The separate `assistantUpgrade` variant is only for the early standalone assistant and is not a normal public download.

[User setup](../docs/v5/GETTING_STARTED.md) · [Build notes](../docs/v5/BUILD.md)

## Source layout

- `app/`: unified navigation, vehicle controls, widgets and automation.
- `openavm-companion/`: library wrapper around `../ZeekrBridgeCompanion/app/src/main`, including playback and secure local transfer.
- `../transfer-protocol`, `../sound-core`, `../localization`: shared modules.

Cloud vehicle login and Recorder pairing are independent. Normal refresh only reads status; the separate temperature action may briefly run climate control. No private server is required.

## Building

Use JDK 17 or newer compatible with the checked-in Gradle wrapper and Android SDK 36. Set your local SDK path in `local.properties`.

The signed distribution includes an AU connection profile. Source builds require a locally supplied app-level profile, passed as `-PzeekrProtocolFile=<local-file>` or placed at `config/private/zeekr-au-166.json`. The build accepts exactly the six expected app-protocol fields and rejects account exports. Never commit this private file, account data or signing material. Ordinary APK users do not need it.

From this directory:

```text
gradlew.bat :app:assembleOpenavmRelease -PzeekrProtocolFile=<local-file>
gradlew.bat :app:testOpenavmReleaseUnitTest :openavm-companion:testReleaseUnitTest :app:lintOpenavmRelease -PzeekrProtocolFile=<local-file>
```

Release builds are not debuggable. The local signing configuration preserves the established maintainer upgrade channel on the maintainer's machine; building with another machine's key does not create an official upgrade.

Tests under `androidTest` use separate QA variants where appropriate. Do not run tests that clear application data against a configured personal installation.
