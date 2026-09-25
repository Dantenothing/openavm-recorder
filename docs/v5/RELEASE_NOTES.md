# OpenAVM V5.0.1 — Phone download restored

**Phone 5.0.1 is the replacement for the withdrawn Phone 5.0.0 APK.** It keeps the complete phone client while removing bundled manufacturer connection parameters. OpenAVM Recorder pairing, recording transfer and playback require no cloud configuration or ZEEKR account.

This is a **public testing release** for compatible ZEEKR vehicles. The vehicle APK is the unchanged Recorder **5.0.0 / 102**; the phone APK is **5.0.1 / 52**. Existing Recorder 5.0.0 users only need to update the phone.

## Downloads

| File | Install on | Version |
| --- | --- | --- |
| [OpenAVM-Recorder-5.0.0-arm64-v8a.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.1/OpenAVM-Recorder-5.0.0-arm64-v8a.apk) | Vehicle head unit | 5.0.0 / 102 |
| [OpenAVM-Phone-5.0.1.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.1/OpenAVM-Phone-5.0.1.apk) | Android phone | 5.0.1 / 52 |
| [connection-config.template.json](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.1/connection-config.template.json) | Empty format example for optional cloud setup; contains no working parameters | |
| [SHA256SUMS.txt](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.0.1/SHA256SUMS.txt) | Verify downloaded files | |

**Recorder works without a phone.** To use the phone only for recordings: install it, choose **Set up later**, open **Media → Connection**, and follow secure pairing. Both devices must share a Wi-Fi network or phone hotspot. Compare the complete phone fingerprint before confirming pairing.

Cloud controls are optional: independently prepare a compatible configuration, import it in **Home → Set up cloud**, then sign in with your authorised account. The app does not include, host or automatically download those parameters. Importing a file alone sends no vehicle requests. See the [cloud setup guide](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/CLOUD_SETUP.md).

[Setup and upgrade guide](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/GETTING_STARTED.md) · [Project overview](https://github.com/Dantenothing/openavm-recorder)

## Vehicle Recorder

- **Recording:** normal and time-lapse modes, with continuous capture across normal surround-recording segment boundaries on supported configurations.
- **Floating live mirror:** preview without recording, individual directions, a four-view grid, Cabin selection, resizing and recording controls.
- **When you return:** keep a Logo entry, restore the full floating preview, or explicitly enable automatic recording when the screen becomes usable again. Choose the behaviour after each installation or update; automatic recording is off by default.
- **Recording library:** date filters, grouped segments and emergency clips make recordings easier to find and keep.
- **Secure phone transfer:** encrypted local connections and pairing that requires comparing the full phone identity fingerprint.
- **Setup help:** revised tutorials and six interface languages, including English.

## Android phone

- **Recordings:** receive, play, export and share vehicle recordings. V5 improves seeking across legacy video segments and brings recording management into the same app as vehicle controls.
- **Vehicle status and widgets:** battery, range, cabin temperature, location and data timestamps; 2×2, 4×1, 4×2 and 4×3 widgets with direct actions and progress feedback.
- **Preconditioning:** set temperature, duration and seat preferences; tap to start and tap again to stop, or schedule a departure.
- **Factory Sentry rules:** optionally enable Sentry after parking away from home and disable it after a confirmed arrival home. Manual choices made in OpenAVM are respected for the current parking session.
- **Separate refresh actions:** ordinary status refresh and a dedicated cabin-temperature button, with clearer progress and results.
- **Optional cloud setup:** local recording features work immediately. Cloud status and controls require user-supplied configuration and login; automation is separately enabled. Setup and help are available in English and Simplified Chinese.

**Phone cloud controls currently target the Australian ZEEKR 7X, using the AU 1.6.6 integration.** Other regions and models are not generally verified, and shared accounts keep only the permissions granted by the owner. This limitation concerns cloud vehicle controls; local recording transfer and playback do not require a ZEEKR account.

## Upgrading from an earlier version

1. **Install over the existing app; do not uninstall first.** Use the official matching-signature APK. Early standalone-assistant users should follow the setup guide before changing installations.
2. **Check camera directions while parked.** The first V5 launch applies the right-hand-drive preset once: Front 1, Rear 2, Left 3, Right 4; Cabin 1, Infrared 0. Later manual adjustments are retained.
3. **Choose your return behaviour again.** Confirm Logo, full preview or the additional automatic-recording option after installing or updating.
4. **Use Recorder 5.0.0 with Phone 5.0.1 for recording transfer.** Existing V5 pairing is retained; V4 connections need secure pairing again once. Compare the complete fingerprint during pairing.
5. **Moving to a new phone?** Pause scheduled departures and Sentry automation on the old phone before enabling them on the new one.
6. **Upgrading Phone 5.0.0 or an older configuration format?** Old cloud configuration and login are removed, and old cloud tasks are stopped. Import and sign in once to use cloud controls again. Recordings, Recorder pairing, home and comfort preferences are retained; review disabled plans and deliberately re-enable the rules you want. The old Phone 5.0.0 APK is not being restored.

## Connection verification for this update

The exact signed Phone 5.0.1 APK passed an Android emulator test using the same transport code as the published Recorder 5.0.0: fingerprint verification, encrypted pairing, session reconnection, WebSocket messaging, real MP4 transfer, receiver restart and resume, SHA-256 verification, media-library indexing and credential revocation. The complete local-media and security suite also passed, including video playback in the embedded player, without Zeekr cloud setup.

These are emulator/code compatibility checks, not a new test in the owner's physical vehicle. Camera access and the actual vehicle/phone Wi-Fi environment remain device dependent. [Verification details](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/PHONE_5_0_1_VERIFICATION.md).

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
