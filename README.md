# OpenAVM V5.1

**Surround-camera recording and a floating live mirror for compatible ZEEKR head units, with an optional Android phone app.**

Current regular release: **Recorder 5.1.0 (134) + Phone 5.1.0 (56)**. Both apps are updated. Install over your existing OpenAVM apps to keep recordings, pairing and settings.

[Vehicle APK](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.1.0/OpenAVM-Recorder-5.1.0-arm64-v8a.apk) · [Phone APK](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.1.0/OpenAVM-Phone-5.1.0.apk) · [Release notes](docs/v5/RELEASE_NOTES.md) · [Setup guide](docs/v5/GETTING_STARTED.md)

## Choose your app

| What you want | Install |
| --- | --- |
| Record video, use the floating mirror, review recordings with HUD and routes | **Recorder** on the vehicle head unit |
| Receive, review, export and share vehicle recordings | **Phone** on Android, then pair with Recorder over shared Wi-Fi or a hotspot |
| Vehicle status, comfort controls, factory Sentry rules and home-screen widgets | **Phone**, then optional user-supplied cloud configuration and authorised login |

Recorder works without a phone. Local pairing, transfer and playback need **no ZEEKR account, cloud configuration or private server**. Phone cloud controls are independent of Recorder and do not stream its live cameras.

## Recorder

- Surround recording in normal and time-lapse modes; optional parallel cabin and infrared recording on supported firmware.
- A floating mirror with directions, a four-view grid, cabin selection, a compact Logo entry and explicit return/automatic-recording choices.
- Optional GPS and vehicle-data capture, live HUD, and session playback with camera views, a timeline, recorded HUD and interactive routes.
- Local route display plus optional OpenFreeMap street maps through MapLibre. The panel opens by default; a saved off choice is respected.
- Recording library, incident protection, original-file export and secure local phone transfer.
- Tested camera handoffs and bounded recovery; compatible adjacent clips retain their playback surface.

## Phone

- Receive and review recordings, recorded routes and vehicle data; play, export and share clips.
- Optional cloud status, climate/seat controls, one-tap preconditioning and scheduled departures.
- Optional rules for factory Sentry when parked away from home and after confirmed arrival home.
- 2×2, 4×1, 4×2 and 4×3 widgets with direct actions and data timestamps.
- Vehicle nickname, appearance and home settings, with English and Simplified Chinese setup help.

**Cloud configuration is optional and supplied by the user.** The public APK does not contain or download manufacturer connection parameters. [Setup instructions and empty template](docs/v5/CLOUD_SETUP.md). The old bundled Phone 5.0.0 APK remains withdrawn.

Normal phone refresh only reads status. The separate temperature button may briefly start climate control and request that this temporary session stop. Read the progress and measurement timestamp; a successful query is not proof of a new measurement.

## Compatibility and limits

| Area | Scope |
| --- | --- |
| Vehicle | Android 8.0+, ARM64, compatible App Lab camera/storage access; installation and firmware matter |
| Phone | Android 8.0+; no iOS app |
| Phone cloud controls | Australian ZEEKR 7X / AU 1.6.6 integration; other regions/models are not generally verified |
| Authorisation | Users sign in with their own authorised account; shared accounts retain their granted permissions |
| Languages | Recorder: English, Simplified/Traditional Chinese, Thai, Vietnamese, Arabic. Phone: English and Simplified Chinese |

Extra cameras, GPS and individual HUD signals depend on the vehicle. Pedal bars use observed historical peaks, not calibrated percentages. Online streets require internet; recordings without GPS still play. A retained window requires the app to remain running: reopen it after a head-unit reboot. This is not continuous parking recording or a safety system.

The owner's latest normal-use feedback is positive. Camera-driver delays, encoder interruptions and device-specific USB discovery can still occur; [release notes](docs/v5/RELEASE_NOTES.md) describe remaining limits, including open [issue #28](https://github.com/Dantenothing/openavm-recorder/issues/28). [Verification record](docs/v5/RELEASE_5_1_0_VERIFICATION.md).

## Getting started and reporting problems

Follow the [setup guide](docs/v5/GETTING_STARTED.md), check camera directions and the actual recording destination while parked, and compare the complete phone fingerprint when pairing. Update both ends for new drive-data playback; V5 pairing is retained, while V4 requires re-pairing. Wait for saving before removing USB and back up important clips.

[Report an issue](https://github.com/Dantenothing/openavm-recorder/issues) with versions, firmware, enabled cameras, destination and exact symptoms. Keep precise locations, footage, account and pairing information private. [Security reporting](SECURITY.md) · [Build instructions](docs/v5/BUILD.md) · [Third-party notices](THIRD_PARTY_NOTICES.md).

OpenAVM is an independent, unofficial project, not affiliated with or endorsed by ZEEKR.

## Source availability and licence status

Copyright © 2026 Dantenothing. All rights reserved.

The source code is publicly visible for inspection and security review. No project-wide open-source licence is granted at this time, and no `LICENSE` file is included.

Except where permitted by applicable law, GitHub's Terms of Service or separate written permission, no permission is granted to copy, modify, redistribute, repackage, publish derivative versions of or sell this project's source code or APK.

The official signed APK published by this repository may be downloaded and used for personal, non-commercial evaluation and testing. This limited permission does not include modification or redistribution.

The AVM Recorder name and logo are not licensed for use in derivative branding or to imply that a modified build is an official release.

Third-party components remain governed by their own licences as described in `THIRD_PARTY_NOTICES.md`.
