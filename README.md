# OpenAVM V5

**Record on the vehicle. Manage everyday vehicle tasks on your phone. Bring recordings with you when you need them.**

OpenAVM is an independent, unofficial project for compatible ZEEKR vehicles. The vehicle app provides recording and a floating live mirror. The Android phone app combines vehicle status, preconditioning, factory Sentry automation and recording management.

[简体中文](README.zh-CN.md) · [Downloads](https://github.com/Dantenothing/openavm-recorder/releases) · [Setup guide](docs/v5/GETTING_STARTED.md) · [Report an issue](https://github.com/Dantenothing/openavm-recorder/issues)

**V5 downloads:** [Vehicle APK](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.0/OpenAVM-Recorder-5.0.0-arm64-v8a.apk) · [Android phone APK](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.0/OpenAVM-Phone-5.0.0.apk) · [Release notes](docs/v5/RELEASE_NOTES.md)

## Which app do I need?

| What you want to do | Download | Install on |
| --- | --- | --- |
| Record, play recordings or use the floating mirror | **OpenAVM Recorder**, filename starting with `OpenAVM-Recorder-` | Vehicle head unit |
| Check status, precondition, automate Sentry or use widgets | **OpenAVM Phone**, filename starting with `OpenAVM-Phone-` | Android phone |
| Transfer vehicle recordings to your phone | Both apps, then pair them | Both devices |

Choose the matching **V5** assets on the release page. The old V4 phone APK is not the V5 companion.

**A phone is optional for vehicle recording. Phone vehicle controls do not require Recorder on the head unit.** No private server is needed: vehicle controls connect to ZEEKR's cloud, while recording transfers use shared Wi-Fi or a phone hotspot.

## Vehicle app

- **Surround recording:** normal and time-lapse modes. Supported configurations keep capture running across normal segment boundaries. Cabin and infrared availability depends on firmware.
- **Floating live mirror:** preview without recording, directional views, a four-view grid, Cabin selection, positioning and resizing.
- **Return recovery:** retain a Logo entry, restore full preview, or explicitly enable recording on return. Confirm your preference after each installation or update.
- **Recording library:** date filters, grouped segments, emergency-video marking and protection for important clips.
- **Transfer and sound tools:** send existing recordings to your phone, or create vehicle sound WAV files from local audio or video.

A retained window is not continuous parking recording. Return recovery requires the app to remain running. Reopen it after a head-unit restart or system termination.

## Android phone app

- **Vehicle status:** cabin temperature, battery, range, location and vehicle state, with data timestamps.
- **Preconditioning:** choose a target temperature, maximum duration and seat preferences. Tap to start, tap again to stop, or schedule a departure.
- **Factory Sentry automation:** enable a rule to turn Sentry on after parking away from home, and optionally turn it off after a confirmed arrival home. Turning it off in OpenAVM pauses automatic activation for that parking session.
- **Four widget sizes:** 2×2, 4×1, 4×2 and 4×3, with direct actions, progress feedback and an app entry.
- **Climate and seats:** use temperature, seat ventilation and heating controls supported by your vehicle and account.
- **Recordings and appearance:** receive, play, export and share recordings; customise the vehicle nickname, appearance and plate style.

**Normal refresh only reads vehicle status.** The separate button next to cabin temperature updates that reading and may briefly start climate control, then request that this temporary session stop. Progress is shown. A successful refresh does not mean the vehicle took a new measurement; check the data timestamp.

Phone automation controls the vehicle's factory Sentry feature. It is separate from Recorder capture and does not remotely start Recorder or stream live vehicle cameras to the phone.

## Compatibility

| Area | Current scope |
| --- | --- |
| Vehicle | Android 8.0+, ARM64, with firmware that permits installation and exposes compatible App Lab camera and storage access |
| Phone | Android 8.0+; no iOS app in this release |
| Cloud vehicle controls | Current baseline: Australian ZEEKR 7X / AU 1.6.6 integration; other regions, vehicles and firmware are not generally verified |
| Account | Existing vehicle authorisation is required; guest accounts retain the permissions granted by the owner |
| Languages | Vehicle: English, Simplified Chinese, Traditional Chinese, Thai, Vietnamese and Arabic. Phone: Simplified Chinese and English |

The phone app includes the current Australian connection configuration. Normal setup does not require a protocol JSON file. Sign in, select the vehicle, then configure home, comfort preferences, widgets and automation as needed.

Use matching V5 apps for recording transfer. Compare the complete fingerprint during initial secure pairing. Connections migrating from V4 need to be paired again once. Received recordings can be played offline.

## Upgrading and practical limits

- Install over the existing app using a package with the same signing identity. **Do not uninstall first.** Early standalone-assistant users should follow the setup guide.
- The first V5 vehicle launch applies the direction preset once: right-hand drive; Front 1, Rear 2, Left 3, Right 4; Cabin 1, Infrared 0. Check it while parked. Later manual adjustments are retained.
- Check the actual recording destination and wait for saving to finish before removing USB. Back up important recordings.
- Phone battery restrictions, network conditions and vehicle sleep can delay updates and automation. Widgets are not continuously live.
- Camera, power and storage problems can interrupt recording. Continuous parking coverage and recovery after system termination are not guaranteed.

See the [setup guide](docs/v5/GETTING_STARTED.md) for instructions and troubleshooting.

## Data and permissions

Login sessions and pairing credentials are encrypted on the phone. The APK does not contain a user's account, vehicle or home location. Vehicle controls contact ZEEKR's cloud; address matching uses the phone's system geocoding service. This is not an entirely local-data workflow.

Recordings are stored on the selected vehicle storage or receiving phone. Paired recording transfers use an encrypted local connection without a private OpenAVM server. Playing received recordings does not require a ZEEKR account.

Grant permissions for the features you use, such as camera, storage, overlay display, notifications and background operation. Configure the vehicle app while parked.

## Feedback

[Report an issue](https://github.com/Dantenothing/openavm-recorder/issues) with the affected app, both app versions, vehicle and firmware, time, steps and exact message. Hide account details, plates, locations and pairing information in screenshots.

## Source availability and licence status

Copyright © 2026 Dantenothing. All rights reserved.

The source code is publicly visible for inspection and security review. No project-wide open-source licence is granted at this time, and no `LICENSE` file is included.

Except where permitted by applicable law, GitHub's Terms of Service or separate written permission, no permission is granted to copy, modify, redistribute, repackage, publish derivative versions of or sell this project's source code or APK.

The official signed APK published by this repository may be downloaded and used for personal, non-commercial evaluation and testing. This limited permission does not include modification or redistribution.

The AVM Recorder name and logo are not licensed for use in derivative branding or to imply that a modified build is an official release.

Third-party components remain governed by their own licences as described in `THIRD_PARTY_NOTICES.md`.
