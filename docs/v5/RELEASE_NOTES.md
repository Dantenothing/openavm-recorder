# OpenAVM V5 - Recorder and Android Companion

V5 brings improved recording and floating-mirror controls to the vehicle app, plus an expanded Android companion for recordings, vehicle status and everyday controls. It is available as a **public testing release** for compatible ZEEKR vehicles.

## Downloads

| File | Install on | Version |
| --- | --- | --- |
| [OpenAVM-Recorder-5.0.0-arm64-v8a.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.0/OpenAVM-Recorder-5.0.0-arm64-v8a.apk) | Vehicle head unit | 5.0.0 / 102 |
| [OpenAVM-Phone-5.0.0.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.0/OpenAVM-Phone-5.0.0.apk) | Android phone | 5.0.0 / 51 |
| [SHA256SUMS.txt](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.0/SHA256SUMS.txt) | Checksums for both APKs | |

**Recorder works without a phone.** Add the phone app when you want to receive, play or export recordings. Phone cloud vehicle controls also work independently of Recorder. No private server is required.

[Setup and upgrade guide](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/GETTING_STARTED.md) · [Project overview](https://github.com/Dantenothing/openavm-recorder)

## Vehicle Recorder

- **Recording:** normal and time-lapse modes, with continuous capture across normal surround-recording segment boundaries on supported configurations.
- **Floating live mirror:** preview without recording, individual directions, a four-view grid, Cabin selection, resizing and recording controls.
- **When you return:** keep a Logo entry, restore the full floating preview, or explicitly enable automatic recording when the screen becomes usable again. Choose the behaviour after each installation or update; automatic recording is off by default.
- **Recording library:** date filters, grouped segments and emergency clips make recordings easier to find and keep.
- **Secure phone transfer:** encrypted local connections and pairing that requires comparing the full phone identity fingerprint.
- **Setup help:** revised tutorials and six interface languages, including English.

## Android companion

- **Recordings:** receive, play, export and share vehicle recordings. V5 improves seeking across legacy video segments and brings recording management into the same app as vehicle controls.
- **Vehicle status and widgets:** battery, range, cabin temperature, location and data timestamps; 2×2, 4×1, 4×2 and 4×3 widgets with direct actions and progress feedback.
- **Preconditioning:** set temperature, duration and seat preferences; tap to start and tap again to stop, or schedule a departure.
- **Factory Sentry rules:** optionally enable Sentry after parking away from home and disable it after a confirmed arrival home. Manual choices made in OpenAVM are respected for the current parking session.
- **Separate refresh actions:** ordinary status refresh and a dedicated cabin-temperature button, with clearer progress and results.
- **Simpler setup:** the current Australian connection configuration is included. Ordinary users do not need to import a protocol JSON file. Onboarding is available in English and Simplified Chinese.

**Phone cloud controls currently target the Australian ZEEKR 7X, using the AU 1.6.6 integration.** Other regions and models are not generally verified, and shared accounts keep only the permissions granted by the owner. This limitation concerns cloud vehicle controls; local recording transfer and playback do not require a ZEEKR account.

## Upgrading from an earlier version

1. **Install over the existing app; do not uninstall first.** Use the official matching-signature APK. Early standalone-assistant users should follow the setup guide before changing installations.
2. **Check camera directions while parked.** The first V5 launch applies the right-hand-drive preset once: Front 1, Rear 2, Left 3, Right 4; Cabin 1, Infrared 0. Later manual adjustments are retained.
3. **Choose your return behaviour again.** Confirm Logo, full preview or the additional automatic-recording option after installing or updating.
4. **Update both apps and pair again once when moving from V4.** Compare the complete fingerprint on both screens during secure pairing. Use the matching V5 APKs above.
5. **Moving to a new phone?** Pause scheduled departures and Sentry automation on the old phone before enabling them on the new one.

## Compatibility and practical limits

Both apps require Android 8.0 or newer. Recorder additionally needs an ARM64 head unit with compatible App Lab camera access, installation permissions and writable storage; Android alone does not establish compatibility.

- A retained floating window can restore preview or request recording while the app remains running. Reopen the app after a head-unit restart or system termination. This feature does not provide continuous parking recording or remote camera viewing.
- Camera availability, vehicle power and storage conditions can interrupt capture. Segment continuity does not cover every interruption. A recording-writer failure observed during earlier testing remains under investigation; see the setup guide for reporting details.
- Check the actual recording destination, wait for saving to finish before removing USB, and back up important clips.
- Normal refresh only reads status. **The separate temperature button may briefly start climate control**, then request that this temporary session stop. A successful read can still return an older vehicle measurement; check the timestamp.
- Phone Sentry automation controls the vehicle's factory feature, independently of Recorder. Android background restrictions, network conditions and vehicle sleep may delay updates and actions. Widgets are not continuously live.

## Feedback

For recording or preview issues, include the app version, vehicle model/region, firmware, recording mode, storage destination, steps and exact error. Say whether the problem affects the live preview, the saved recording or both. For pairing and transfer issues, include both app versions.

[Report an issue](https://github.com/Dantenothing/openavm-recorder/issues), keeping account details, locations, number plates, pairing information and private footage out of public reports. For sensitive security findings, follow [the reporting policy](https://github.com/Dantenothing/openavm-recorder/blob/main/SECURITY.md).

OpenAVM is an independent, unofficial project, not affiliated with or endorsed by ZEEKR.
