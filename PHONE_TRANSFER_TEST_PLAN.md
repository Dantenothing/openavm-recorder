# V4 maintainer regression checklist

Use this checklist only while parked. The phone and head unit must share a
trusted hotspot or private LAN; the authenticated local transfer is not
end-to-end encrypted.

This checklist is for subsequent changes and device-specific regression checks.
The V4 owner acceptance and automated build results are recorded in
[RELEASE_VERIFICATION.json](RELEASE_VERIFICATION.json).

The two release build outputs are:

- Head unit: `app/build/outputs/apk/release/app-release.apk`
- Android phone: `ZeekrBridgeCompanion/app/build/outputs/apk/release/app-release.apk`

## Pairing and transfer notification

1. Start the receiver from OpenAVM Companion's **Vehicle** page and generate a
   six-digit pairing code.
2. On the head unit, open **Phone**, pair or reconnect, then send one finalized
   recording or selected one-minute segment.
3. Confirm the phone receives exactly one playable item and the head-unit
   transfer task reaches **Completed**.
4. Leave the notification shade open for at least 15 seconds. The recording
   transfer notification must disappear automatically without restarting the
   app. The persistent Companion receiver notification may remain while the
   receiver itself is running.

## Sound relay notification

1. In Companion's sound maker, send a short valid WAV to the paired vehicle.
2. Confirm the car verifies both supported USB sound folders and reports
   completion.
3. Confirm the sound-relay notification disappears automatically within
   15 seconds. If the Zeekr sound list has not refreshed, leave and re-enter the
   vehicle session before judging the installed WAV.

## Recorder and USB regression

1. Record for at least two one-minute segments with USB preferred.
2. Open the recording and confirm its full duration and both segment entries.
3. Send only the second segment and verify that the phone receives only that
   selection.
4. Delete one manifest-owned OpenAVM USB recording from the library and refresh.
   Confirm it disappears and factory `/SentryMode/` remains unchanged.
5. Unplug USB only after recording has stopped, wait for the library to show it
   offline, reinsert it, refresh and confirm its recordings return without a
   crash.

## Release UI

1. After a fresh install/update, confirm Gate, Fast Track, JSON report and Git
   SHA controls are absent from normal Settings, Library, Phone and USB pages.
2. Tap the version row seven times; confirm developer tools appear and Storage
   diagnostics can be opened.
3. Tap **Disable developer mode** and confirm those controls disappear again.

Investigate before publishing a new release if either completed-transfer notification remains,
factory Sentry content changes, the recorder fails to stop, or the app crashes.
