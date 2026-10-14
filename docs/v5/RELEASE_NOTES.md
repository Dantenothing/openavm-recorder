# OpenAVM V5.1 — Recorder & Phone

A regular release of **Recorder 5.1.0 (134)** and **Phone 5.1.0 (56)**. Both apps are updated. Install over your existing OpenAVM apps to keep recordings, pairing and settings.

## Downloads

| Install on | File |
| --- | --- |
| Vehicle head unit (ARM64) | [OpenAVM-Recorder-5.1.0-arm64-v8a.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.1.0/OpenAVM-Recorder-5.1.0-arm64-v8a.apk) |
| Android phone | [OpenAVM-Phone-5.1.0.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.1.0/OpenAVM-Phone-5.1.0.apk) |
| File verification | [SHA256SUMS.txt](https://github.com/Dantenothing/openavm-recorder/releases/download/v5.1.0/SHA256SUMS.txt) |

[Setup guide](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/GETTING_STARTED.md) · [Verification and limits](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/RELEASE_5_1_0_VERIFICATION.md)

## What's new on the vehicle

- **Optional multi-camera recording:** record surround together with cabin and/or infrared channels where the head unit supports them. Selected channels use the same chosen recording destination. Enable additional cameras in settings; they are not required for ordinary surround recording.
- **Driving data and HUD:** optionally record GPS and available vehicle signals alongside video. View speed, gear, indicators and available pedal readings live and in playback. Pedal bars are relative to observed historical peaks, not calibrated pedal percentages; missing data remains unavailable.
- **Session playback:** a clip list, multi-camera views, shared timeline, recorded HUD and route panel. Enlarge individual views, seek across compatible adjacent clips, select clips or a time range, and transfer or export recordings.
- **Maps:** view a local recorded trace or opt into online street maps. The map panel opens by default unless you have saved it off; the street-map choice is remembered. Pan, zoom and follow the recorded position. Old recordings without GPS remain playable.
- **Recording and preview stability:** retains the tested camera connection across eligible preview/record/stop handoffs, waits for acknowledged resource release, and handles same-size cabin encoder-format changes at safe file boundaries. Recovery now keeps waiting for its own closing preview within the existing deadline instead of cancelling at an intermediate warning.
- **Smoother review:** visible thumbnails load without holding up playback; compatible neighbouring segments share their player and output surface. Actual recording gaps remain visible.
- **Floating window:** select the cabin feed while it is being recorded without restarting the recording, with clearer live HUD and return/setup choices.

## What's new on the phone

- Receive optional recorded GPS/vehicle data together with videos and review the session timeline, HUD and route.
- A map panel that opens by default, with remembered local-trace/street-map choice and map interaction improvements.
- Keeps secure local pairing, media management, video playback/export, widgets and optional cloud vehicle controls in one app.

Local Recorder pairing, transfer and playback **do not require a ZEEKR account or cloud configuration**. The public phone APK does not include, host or automatically download manufacturer connection parameters. Optional cloud features still require a user-supplied configuration and authorised login; automation is separately enabled. [Cloud setup and empty template](https://github.com/Dantenothing/openavm-recorder/blob/main/docs/v5/CLOUD_SETUP.md).

## Upgrade notes

1. Stop recording, wait for saving to finish, then install each matching APK over the existing app. Do not uninstall first. Recorder 134 also upgrades the private support builds through 133.
2. Review **Return, recording & playback** after updating: floating-window behaviour, optional automatic recording, extra cameras, driving-data capture and map display. Saving choices does not immediately start a recording.
3. Check camera directions and the actual recording destination while parked. USB preferred may use internal storage if no single supported, writable USB target is available.
4. Update both apps to use the new drive-data playback together. Existing V5 pairing is retained; V4 connections require secure re-pairing. Compare the complete phone fingerprint when pairing.
5. Phone users with an existing valid user-imported configuration retain it. The older embedded configuration format still requires a new import/sign-in, with old automation stopped. The withdrawn Phone 5.0.0 APK remains withdrawn.

## Compatibility and known limits

Both apps require Android 8.0+. Recorder needs an ARM64 head unit with compatible App Lab camera access. Camera concurrency, resolution, vehicle signals and GPS availability vary by firmware. The current phone cloud-control baseline is Australian ZEEKR 7X / AU 1.6.6; this restriction does not apply to account-free local recording transfer.

The owner reported satisfactory normal use of the latest recorder candidate on 14 October 2026 and requested this release. Automated verification is recorded separately; neither establishes compatibility with every vehicle or every camera/USB combination.

- Returning to the car or stopping recording can still involve waiting for the camera driver to release resources. A vendor-specific stall or unexpected encoder timestamp can interrupt recording; not every earlier hardware failure has been independently reproduced or proven eliminated. Check recording status and preserve diagnostics if it happens.
- Reopen OpenAVM after a head-unit reboot or system termination. A retained floating window is not continuous parking recording and does not remotely wake the vehicle.
- [Issue #28](https://github.com/Dantenothing/openavm-recorder/issues/28), USB not being selected on one reported setup, remains open pending device evidence. This release does not claim to resolve that report.
- Drive data is stored locally and accompanies transfer only when selected. Online maps use OpenFreeMap with MapLibre and request map resources from their providers; they need internet. No satellite view or offline region download is included.
- Historical-peak pedal bars are an approximation. Multiple channels use recorded timing evidence; hardware frame synchronisation is not guaranteed.
- Normal phone refresh reads status. The separate cabin-temperature action may briefly run climate control. Widgets and automation depend on Android/network/vehicle availability.

## Feedback

Include the app version, vehicle/firmware, enabled cameras, actual storage destination, exact error and whether the issue affects preview, saved video or both. Use **Settings → Recording diagnostics** to save a diagnostic file when useful. Keep precise routes, account data, connection parameters, pairing information and private footage out of public issues. [Security reporting](https://github.com/Dantenothing/openavm-recorder/blob/main/SECURITY.md).

OpenAVM is independent and unofficial, with no affiliation to or endorsement by ZEEKR.
