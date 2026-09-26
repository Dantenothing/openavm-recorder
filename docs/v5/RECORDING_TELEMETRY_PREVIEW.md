# Location and speed recording — private development preview

Recorder **5.1.0-telemetry1**, version code **103**. This is an internal test build, not a published V5 update. The current public Recorder and Phone releases are unchanged.

## What is implemented

An optional **Settings → Location & speed** switch records Android location samples alongside a recording session. It is **off by default**, including after upgrading from V5. Enabling it requires a separate location permission grant. No Zeekr account, cloud configuration or private server is involved.

While the recorder is actually recording, it asks available Android GPS, network or system-fused providers for updates, nominally once per second. It displays whether it is waiting for positioning, recording locations, or experiencing a gap. A location failure does not request a camera stop. There is no new wake lock or separate location service that restarts the recorder.

In Library, open a recording and tap **Route**. The view shows the session's local trace and the available speed at the selected video time. The ordinary video view also shows a small speed label when a local track exists. Scrubbing across segments uses each segment's own monotonic start time, including gaps between files.

Speed is supplied by the Android location provider, **not read from the vehicle's speedometer/CAN bus**. Missing speed is displayed as unavailable; a reported zero is preserved. We do not calculate speed from noisy position differences.

## What remains unverified or unavailable

- **Vehicle GPS access has not been tested.** Having a factory navigation app does not prove that a third-party app receives Android location updates. The vehicle may expose no usable provider, or may withhold fixes or speed.
- Video alignment uses existing same-session, same-boot elapsed-time metadata. It is approximate; sensor-to-video latency has not been measured on the vehicle.
- Time-lapse recordings can show their stored route, but do not yet show synchronized speed/cursor playback. Older videos without the required session/timing metadata do not get an inferred track.
- Tracks stay on the recording head unit in this preview. Phone transfers, MP4 metadata, video exports and screenshots of video alone do not include them. The existing Phone app and transfer protocol are unchanged.
- The route is a local trace without street-map tiles. There is no online map SDK, reverse geocoding, live speed watermark, or burned-in export.
- If Android rejects starting a location foreground service during automatic return, video can continue without location. Open OpenAVM and start a new recording to retry. If location permission or the system provider is disabled mid-session, location collection stops; re-enable it and start a new recording.

## First vehicle check, when convenient

1. Park outdoors. Install the preview over Recorder V5; existing recordings and pairing should remain. Confirm the displayed version is `5.1.0-telemetry1`.
2. Open **Settings → Location & speed**, enable the switch, and allow precise location while using the app. If offered, also enable system Location. Leave the switch off if you only want normal video recording.
3. Start a short normal recording. Check whether the status changes from waiting for an accurate location to recording location. Waiting alone is not evidence of a working GPS connection.
4. Stop and open the recording in Library. Tap **Route**. At rest, zero or a small fluctuating speed can occur; an unavailable speed is not converted to zero. Check the speed label and seek into another segment.
5. For a later trip, start recording before driving and review it after parking. Check that the route follows the trip, gaps remain gaps, and speeds are plausible. No screen interaction is needed while driving.
6. Check manual stop, the existing leave/return behavior and a recording-source switch. Verify ordinary recording still works with the option off and with positioning unavailable.

Useful feedback: whether a location provider produces a fix; waiting/recording/interrupted status; whether speed is present; whether video and location remain roughly aligned after a segment jump. Do not post precise route files or screenshots of private locations to a public issue.

## Data handling and limits

Each session uses an append-only JSONL journal under Android's app-private **no-backup** directory. UUID session IDs associate it with existing video sidecars; no coordinates are added to those sidecars or the transfer/diagnostic payloads. Application backup is disabled.

The collector rejects mock locations, samples from before capture started, samples older than five seconds, invalid coordinates, reported accuracy worse than 50 metres, and implausible short-interval jumps. Fresh GPS takes priority over a competing non-GPS fix. Rejected data, source changes, pauses and gaps over five seconds split the trace. Playback shows only a preceding sample at most two seconds old; it does not interpolate across gaps or use a future sample.

Journals are bounded to 28,800 points and 8 MiB per session, and 64 MiB in total. Older sessions are pruned to reserve space when a new recording starts; that is also when the 30-day age policy is applied. There is no independent scheduled cleanup while the feature is unused. If a session reaches its limit, location collection stops and video continues.

**Clear location history** deletes local tracks and speed samples while leaving videos intact. Stop recording before clearing. Disabling the option stops further collection but keeps already saved history until it is cleared or pruned. Deleting an individual video does not immediately remove its session journal, because other segments may still reference it.

## Implementation and verification

- `telemetry/TelemetryModel.kt`: sample validation, speed semantics, freshness and playback lookup.
- `telemetry/TelemetryStore.kt`: bounded journal, truncated-tail recovery, session checks, retention and protected deletion.
- `telemetry/RecordingTelemetryCapture.kt`: Android provider adapter, serial worker, recorder lifecycle and late-callback rejection.
- `telemetry/TelemetryProjection.kt`: local route projection with date-line handling and explicit path breaks.
- `ui/product/TelemetryUi.kt`: English/Simplified Chinese setup, status, local history clearing and route view. Other app languages currently use their existing English fallback for these new strings.
- `CameraRecordingService` declares camera and location foreground-service types, but only attempts the location type after opt-in and a permission check. A location-type rejection is handled separately from camera startup.

The automated provider/lifecycle tests inject **synthetic samples** and do not establish vehicle GPS support. Vehicle acceptance is deliberately still pending.

Verified on 26 September 2026:

- Recorder unit suite: **1,141 tests, zero failures/errors/skips**, including 26 new telemetry tests.
- Telemetry Android tests: **15 passed** on an Android 16 emulator, including late callbacks, session transitions, opt-out, permission loss, stale data, storage limits and English/Chinese UI behavior.
- Existing product tabs/settings, seven-page guide and installation-choice UI tests: passed across the app's supported languages. The initial tabs test was obscured by Android's camera permission dialog; after explicitly fixing camera permission to denied on the emulator, the unchanged test passed. No production workaround was added for that test setup.
- Visual review: 1920 × 1080 emulator screenshots, including English and Simplified Chinese routes and settings. These show synthetic test data.
- Debug/instrumentation and ARM64 release builds succeeded. Lint completed with no errors and no findings in the new telemetry files; the repository still has 199 warnings and 19 hints in other files.
- The preview and public Recorder 5.0.0 APK use the same signing certificate. The preview retains the application ID and increases its version code to 103. A real vehicle upgrade has not been attempted.

No vehicle was connected, no real GPS fix was captured, and no public release was changed during this work. The emulator was shut down by the host restart after its completed tests; the saved test reports and screenshots were checked afterwards.

Build and test from the Recorder root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintDebug -PpublicUiCheck=true
$env:ANDROID_SERIAL = 'emulator-5580'
.\gradlew.bat :app:connectedDebugAndroidTest -PpublicUiCheck=true '-Pandroid.testInstrumentationRunnerArguments.package=com.dante.zeekrcapabilitylab.telemetry'
```

Use an emulator for the instrumentation tests. They are guarded to run only on an Android emulator and do not request camera recording or vehicle commands.
