# OpenAVM V4

[简体中文](README.zh-CN.md) · [Download V4](https://github.com/Dantenothing/openavm-recorder/releases/tag/v4.0.0) · **[Technical explanation](PLATFORM_NOTES.md)** · [User guide](USER_GUIDE.md) · [Release notes](GITHUB_RELEASE_V4.md)

Record, browse and transfer video on compatible Zeekr App Lab head units. V4 includes **OpenAVM Recorder** for the vehicle and an **optional OpenAVM Companion** app for Android phones.

## Downloads

| App | Install on | Version | Download |
| --- | --- | --- | --- |
| OpenAVM Recorder | Compatible ARM64 vehicle head unit | 4.0.0 (57) | [Vehicle APK — 2.24 MB](https://github.com/Dantenothing/openavm-recorder/releases/download/v4.0.0/OpenAVM-Recorder-V4-arm64-v8a.apk) |
| OpenAVM Companion | Android phone, Android 8.0 or later | 4.0.0 (29) | [Optional phone APK — 3.15 MB](https://github.com/Dantenothing/openavm-recorder/releases/download/v4.0.0/OpenAVM-Companion-V4.apk) |

The vehicle app also requires Android 8.0 or later and compatible App Lab camera/storage access; the Android version alone does not establish compatibility. Signed APKs and SHA-256 checksums are attached to the release. Use the vehicle's available App Lab installation flow. Existing installations with the same application ID and signing key can be updated in place.

**The phone APK is not required.** The vehicle app and a USB drive cover recording, video viewing/management and lock/unlock sound creation. The phone app adds wireless transfer and phone-side playback, trimming and individual-view export.

## How recording works

For surround-view recording on the tested vehicle, OpenAVM uses Android Camera2 to request one processed `1280×5140` video stream containing four approximately `1280×1280` front, rear, left and right views. The app arranges these views into a 2×2 preview and records the combined stream through Android media APIs. It uses the camera access exposed by App Lab, without requiring root or modifying vehicle firmware.

The upstream camera pipeline and its resource sharing with other vehicle systems have not been fully established. Camera temperatures have not been specifically measured, and dedicated thermal testing has not been completed.

The **[detailed technical explanation](PLATFORM_NOTES.md)** covers camera access, CPU/GPU/RAM and storage questions, road-test observations, camera ownership, and known limitations. It preserves the technical material previously shown on the project homepage, with dated observations and current V4 notes.

## What V4 includes

- **Direct USB recording:** video writes straight to the selected writable USB drive, without first recording the video to internal storage. Small settings, metadata and diagnostics still use internal storage; an unavailable USB can cause a bounded internal-storage fallback when recording remains appropriate.
- **Recording choices:** four-view surround recording, selectable Cabin and IR sources, and time-lapse. The 2×, 5× and 10× settings have received targeted owner testing. Camera availability and declared resolutions depend on the vehicle.
- **A clearer library:** categories, session grouping of one-minute files, selected-segment transfer, internal-to-USB export and bulk deletion of OpenAVM-owned recordings. Factory Sentry recordings can be viewed and transferred; factory `/SentryMode/` remains read-only.
- **Optional Android Companion:** local hotspot/LAN pairing, video transfer, immediate playback in the detail page, correct square-video proportions, trimming and individual-camera export. A USB drive can also be connected directly to the phone.
- **Sound tools on both apps:** import supported music/video, trim audio, make vehicle-compatible WAV files and save to USB. The vehicle toolbox can browse, preview and delete existing USB sound files.
- **Six language choices:** English, Simplified Chinese, Traditional Chinese, Thai, Vietnamese and Arabic, plus follow system. Both apps use the same icon; advanced vehicle diagnostics are hidden by default.

## A few things to know

- On the owner's car, normal recording usually continues for around five minutes after leaving, which can help cover part of the gap before factory Sentry starts. This is **observed vehicle behaviour**, not a fixed timer or guaranteed handover. V4 does not start or control factory Sentry.
- New lock/unlock sounds may not appear in the vehicle's sound list immediately. In testing, leaving and returning to the car, or reconnecting the USB drive, sometimes refreshed the list. OpenAVM cannot identify which sound the vehicle currently has selected.
- **Custom parking Sentry and automatic recording on return are not included.** Those experiments are paused because reliable camera access and wake-up across vehicle sleep have not been established. Factory Sentry video browsing remains available. See the [research status](research/sentry/README_V4_ARCHIVE.md).
- Testing has mainly used the owner's car and Android devices. Other vehicle models, firmware and phones may behave differently. Earlier development builds experienced a persistent third-party camera-feed stall requiring a head-unit restart; the owner reports no recurrence in current V4 testing. This is not proof that it cannot recur.

OpenAVM is independent, unofficial software and is not affiliated with or endorsed by Zeekr. It is not a replacement for a factory safety system or a guaranteed parking recorder. Operate its controls while parked. See [the technical explanation and limitations](PLATFORM_NOTES.md) and [security reporting](SECURITY.md).

## Guides and development

- [Setup, phone pairing, USB, sound tools and troubleshooting](USER_GUIDE.md)
- [中文使用说明](USER_GUIDE.zh-CN.md)
- [V4 release notes](GITHUB_RELEASE_V4.md) and [changelog](CHANGELOG.md)
- [Build instructions and release verification](DEVELOPMENT.md)
- [Detailed technical explanation and test observations](PLATFORM_NOTES.md)
- [Paused Sentry research](research/sentry/README_V4_ARCHIVE.md)

## Source availability and licence status


Copyright © 2026 Dantenothing. All rights reserved.

The source code is publicly visible for inspection and security review. No project-wide open-source licence is granted at this time, and no `LICENSE` file is included.

Except where permitted by applicable law, GitHub's Terms of Service or separate written permission, no permission is granted to copy, modify, redistribute, repackage, publish derivative versions of or sell this project's source code or APK.

The official signed APK published by this repository may be downloaded and used for personal, non-commercial evaluation and testing. This limited permission does not include modification or redistribution.

The AVM Recorder name and logo are not licensed for use in derivative branding or to imply that a modified build is an official release.

Third-party components remain governed by their own licences as described in `THIRD_PARTY_NOTICES.md`.
