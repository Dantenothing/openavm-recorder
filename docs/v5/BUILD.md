# V5 build and compatibility notes

[Project overview](../../README.md) · [User guide](GETTING_STARTED.md)

## Two applications

| Application | Gradle project | Application ID | Version |
| --- | --- | --- | --- |
| Vehicle Recorder | Repository root, `:app` | `io.github.dantenothing.openavmrecorder` | 5.0.0 / 102 |
| Android Phone | `phone-unified/`, `:app`, `openavm` flavour | `com.dante.zeekrbridge` | 5.0.1 / 52 (candidate) |

The former standalone Companion project is retained as source for the unified phone library. Build the public phone APK from `phone-unified/`, not from the legacy standalone entry point.

Both applications require Android 8.0 or newer. Vehicle hardware/firmware must additionally provide supported camera, storage and installation capabilities.

## Local builds

Use the checked-in Gradle wrappers and a compatible JDK (17 or newer) with Android SDK 36. Store SDK paths and signing keys outside version control.

Vehicle, from the repository root:

```text
gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Phone: follow [the phone build instructions](../../phone-unified/README.md). No private protocol file is required or consumed. Actual cloud connection parameters are supplied by the user at runtime, not bundled in public artifacts. See [optional cloud setup](CLOUD_SETUP.md).

The existing distribution channel uses the maintainer's established signing identity. A build signed with another key cannot update that channel in place. APK debuggability is checked independently of certificate naming.

## Connection compatibility

- Media business protocol remains version 1.
- Secure pairing and authenticated transfer use security version 2.
- Pairing compares the complete phone public-key fingerprint before sending the pairing request through the verified encrypted connection.
- V5 media handling recognises the declared recording layout, including `OPENAVM_VERTICAL_STRIPS_TO_COLUMNS_V1`; version names alone do not establish media compatibility.
- Migrating from old V4 connections requires updating both ends and pairing again once. There is no privileged plaintext fallback.

## Verification scope

The release process checks package identity, increasing version codes, signing identity, the single launcher, non-debuggable release manifests, bundled notices, absence of bundled parameters, import/migration behaviour, builds, tests, lint and download hashes.

Owner-reported vehicle/phone connectivity is recorded separately from automated checks. The owner confirmed the two ends worked together on 2026-09-26; that report does not identify every device, scenario or final APK hash. Do not turn it into an all-hardware or all-scenarios claim.

Certificates and keys under `app/src/test/resources/phone-security/` are deliberately synthetic unit-test identities. They are never installed as device identities or used to sign application packages. Real phone TLS identities are generated per installation with Android Keystore.

The current public V5 release contains the vehicle APK and its checksum file. The phone candidate requires separate verification before publication. Local configuration, signing keys, personal captures and private diagnostic archives are not release assets.

Run the artifact guard against the actual APK that will be uploaded:

```text
python phone-unified/check_public_artifact.py phone-unified/app/build/outputs/apk/openavm/release/app-openavm-release.apk
```

The maintainer can add `--reference-apk <privately-retained-old-apk>` to compare all uncompressed entries against the six legacy configuration values. Output contains field names and findings, never the values. The old bundled APK is a negative control and must fail. Keep it private. This guard does not prove the absence of every possible secret or establish permission for third-party cloud access.
