# OpenAVM V4 user guide

[Home](README.md) · [简体中文](USER_GUIDE.zh-CN.md) · [Downloads](https://github.com/Dantenothing/openavm-recorder/releases/tag/v4.0.0)

## Install or update

1. Install `OpenAVM-Recorder-V4-arm64-v8a.apk` on a compatible vehicle head unit using its available App Lab installation flow.
2. If you want phone transfer or phone-side editing, install `OpenAVM-Companion-V4.apk` on an Android phone. The phone app is optional.
3. Grant the permissions requested for the features you use. Android 8.0+ alone does not guarantee a compatible camera feed or USB implementation.
4. Existing installations with the same application ID and signing key can be updated in place. Do not uninstall simply to update: uninstalling can remove app-internal recordings and settings. A separately built APK may have a different key and cannot update the official installation.

The vehicle release contains ARM64 libraries only. The phone release includes ARM64, ARMv7, x86 and x86_64 libraries. Neither APK includes the retired parking Sentry AI model or OpenCV runtime.

## Record to USB

Connect a writable USB drive recognised by the head unit. In recording/storage settings, use USB-preferred storage and check the app's indicated destination before starting. Choose normal or time-lapse recording and the desired camera source, then start recording.

USB video data is written directly to the selected removable volume, under `Download/OpenAVM`. Small internal metadata, recovery journals, settings and diagnostics remain necessary. If USB becomes unavailable, recording can fall back to internal storage at most once within that recording session when camera/power conditions allow it. V4 blocks this fallback when vehicle-away power evidence requires the recording to stop. Reconnecting a drive does not silently move an already-fallback session back to USB; check the destination when starting the next session.

The USB quota defaults to 30 GiB, with 15/30/45 GiB presets and a custom option. Automatic quota cleanup is limited to verified OpenAVM-owned files; factory Sentry and unrelated USB files are excluded. Save/export important footage before relying on automatic rotation. Stop active recording or transfer before removing the drive.

## Leaving the vehicle and time-lapse

On the owner's car, normal recording usually continues for roughly five minutes after leaving, before stopping as vehicle power/display state changes. This can help cover some of the gap before factory Sentry starts, but is not a fixed timer, guaranteed parking coverage or controlled handover to factory Sentry.

Time-lapse has its own stop policy. The 2×, 5× and 10× options have received targeted owner testing; they do not establish validation of every higher rate or vehicle. V4 does not guarantee the app remains awake through vehicle sleep and does not start recording automatically when you return.

## Browse and manage recordings

The library distinguishes internal recordings, OpenAVM USB recordings and factory Sentry media. One-minute segments from the same modern OpenAVM recording session appear together as one recording. This is a library grouping, not an automatic merge into a new large MP4. Older files without session identity may remain separate.

Open a recording to play it, navigate its segments, or select the segments to send to your phone. Use the library's USB export action to copy older OpenAVM internal recordings to the connected drive. Verify the USB copy before deleting the original.

Multi-select and bulk deletion apply to OpenAVM-managed files. Factory `/SentryMode/` content remains read-only and is excluded from deletion and quota cleanup. Protecting/saving an OpenAVM event prevents automatic cleanup from removing it.

## Pair the Android phone

1. Enable the phone's hotspot and connect the car to it. A trusted shared local network is also supported.
2. Open OpenAVM Companion on the phone and start its receiving service.
3. Open the vehicle app's **Phone** tab. Enter the IP/endpoint displayed by Companion and the six-digit pairing code.
4. After pairing succeeds, open a recording in the vehicle library and send the whole recording or selected segments.

The initial pairing is remembered. Normally the pairing code is not needed again; keep both devices on the same network and start the receiving/connection services. Use reconnect if necessary, and check the current phone address if its hotspot/network address changes. Clearing app data, reinstalling or resetting pairing can require pairing again.

Keep the hotspot active and preferably keep Companion in the foreground while transferring. Background restrictions differ between phones. The receiver uses the local network; internet access is not required for local transfers.

## Phone playback, export and direct USB

Opening received video starts playback in its detail page. Controls, segment selection and export actions are on that page. Square single-camera exports retain their aspect ratio; portrait/rotated videos fit their available display area.

Use export to trim a time range or extract a camera view from supported composite footage, then save/share the result. Single-view extraction is a phone export operation, not a front-only capture mode on the vehicle.

As an alternative to wireless transfer, connect the USB drive directly to an Android phone, using an adapter if needed. Grant access to the required folder through Android's file picker, then browse/import supported media and use the phone's editing tools. Supported USB devices and file-system access depend on the phone. Wireless connection and direct USB share media workflows but are not identical connection modes.

## Create lock/unlock sounds

On either app, open the sound toolbox, import a supported local audio/video file, select the desired section and export a vehicle-compatible **WAV** file. MP3 can be an input; the finished vehicle sound is WAV, not MP3. Files must be accessible and decodable by the device.

- **Phone + vehicle connection:** send the completed sound through the paired vehicle app to the car's connected USB.
- **Phone + direct USB:** save the completed sound to the drive connected to the phone.
- **Vehicle only:** download a supported file using Brave or another browser, import it into the vehicle toolbox, trim/extract the audio and save it to USB.

The supported vehicle USB sound folders are `/Lock Status Tones/` and `/解闭锁音效/`. The vehicle toolbox can list, preview and delete exact WAV files in these folders. It cannot identify or change the sound currently selected in the factory interface; select the new sound using the vehicle's own settings.

**The factory sound list may refresh late.** During testing, leaving and returning to the car, or reconnecting the USB drive after file operations had finished, sometimes made a new sound appear. Not seeing it immediately does not by itself mean the export or transfer failed.

## Language and diagnostics

Both apps offer English, Simplified Chinese, Traditional Chinese, Thai, Vietnamese and Arabic, plus follow system. Native-speaker feedback on wording is welcome.

Vehicle advanced tools are hidden by default. To expose them, open **Settings → About & privacy** and tap the version seven times. Open **Developer tools → Recording diagnostics** to copy JSON directly. If the report is split, keep all parts of the same report together. A ZIP export is not required.

Developer mode can be disabled in the About area. If it was enabled in an older build, that preference remains enabled after an update.

For a public issue, include app version, vehicle model/firmware, relevant mode/storage choice, a short timeline and reproduction steps. Inspect diagnostics before sharing and follow [SECURITY.md](SECURITY.md); avoid posting personal data or full private recordings.

## Known limits

- V4 has targeted owner testing, not broad vehicle/firmware or exhaustive long-duration acceptance.
- Earlier development versions experienced a persistent camera-feed stall that could survive closing or uninstalling the app and required a head-unit restart. The owner observed factory 360 still working in those tests and reports no recurrence in current V4 testing. These observations are not a guarantee about every failure or vehicle. Stop using the app if abnormal factory behaviour appears; record the circumstances and follow the vehicle's documented restart procedure when necessary.
- The RC2 extra-internal-clip fix was retested by the owner with no recurrence reported; V4 retains that recorder implementation.
- Full-disk, repeated hard-unplug, all background phone states and multi-day quota rotation are not exhaustively validated.
- Custom parking Sentry and automatic recording on return are paused. Factory Sentry playback/transfer remains available. See [research status](research/sentry/README_V4_ARCHIVE.md).
