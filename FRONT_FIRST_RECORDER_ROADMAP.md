# Front-First Recorder Implementation Roadmap

## Purpose

Evolve AVM Recorder into a usable, stable, and cautious dashcam with two explicit recording modes:

- `FRONT_ONLY` — a candidate future default only after every vehicle-validation gate passes. Record only the verified front view to reduce encoded data and internal-flash writes.
- `SURROUND_360` — retain full composite recording for users who accept the higher storage and resource cost.

Front-only recording must create a front-only encoded file. Showing a front crop while continuing to encode the full `1280x5140` composite does not meet this goal.

This roadmap does not claim that front-only recording eliminates impact on OEM camera, AVM, ADAS, ISP, memory, GPU, thermal, or storage resources. If the only source is the composite stream, GPU cropping can reduce encoder load and file writes but may not reduce upstream camera or memory bandwidth.

## Non-negotiable behavior

1. OEM/factory camera functions always have priority.
2. Recording starts only from an explicit user action; no boot or process-restart auto-start.
3. `FRONT_ONLY` never silently falls back to `SURROUND_360`.
4. A source or calibration that cannot be positively identified blocks front-only recording with a useful error.
5. Preview surfaces owned by an activity are never required to keep recording alive.
6. Protected or pinned recordings are never deleted automatically.
7. Segment finalization is crash-recoverable and never presents an unverified file as healthy.
8. Presentation changes such as zoom, lens correction, or 2x2 layout never alter existing recordings.
9. New user-visible behavior and limitations are documented in English and Simplified Chinese.
10. JVM or emulator tests are not presented as proof of safe operation on a vehicle.

## Target architecture

```text
Verified Camera2 source
          |
          +-- verified direct front stream, if available
          |             |
          |             v
          |       surface video encoder
          |
          +-- verified composite stream
                        |
                        v
             service-owned SurfaceTexture
                        |
                        v
              EGL/GPU front-lane crop
                        |
                        v
                 surface video encoder

Encoder -> muxer -> partial segment -> durable finalization -> MP4 + sidecar
```

`SURROUND_360` may initially retain the existing direct Camera2-to-`MediaRecorder` path. Both modes should ultimately implement a common `RecordingPipeline` contract so service state, health, storage, incident protection, and recovery behave consistently.

Use a surface-to-surface GPU path for composite cropping. Do not add full-resolution CPU frame copies, unbounded frame queues, or duplicate temporary video files.

## Provisional product targets

These are engineering gates, not current capabilities. Confirm them against a measured baseline and revise only with recorded evidence.

| Area | Initial target |
| --- | --- |
| Front-only output | Verified front view, playable H.264 MP4, no composite lanes |
| Initial front profile | Capability-selected output near `1280x1280`, 30 fps, 6-8 Mbit/s |
| Front storage rate | At most 4 GB/hour at the selected normal profile |
| Segment duration | 60 seconds initially, configurable only within validated bounds |
| Normal rollover gap | At most 1 second; target below 250 ms using encoded timestamps |
| Unexpected-stop loss | At most the active segment duration; recover every previously completed segment |
| Camera return | Resume attempt within 5 seconds after confirmed availability, with bounded backoff |
| Storage safety | Zero deletion of protected, upload-pinned, playback-pinned, current, partial, unknown, or sidecar-less files |
| Endurance | 24-hour front-only soak without crash, deadlock, runaway storage, or thermal shutdown |
| OEM coexistence | 200 repeatable OEM-camera takeover/return cycles without parallel reopen loops |

At 8 Mbit/s, video payload is approximately 1 MB/s, 60 MB/minute, or 3.6 GB/hour before container overhead. The existing 28 Mbit/s setting is approximately 3.5 MB/s, 210 MB/minute, or 12.6 GB/hour. Actual values must be measured on produced files.

## Delivery phases

### Phase 0 — Capture a repeatable baseline

Before changing the pipeline:

- Record CPU, memory, GPU/thermal observations, file growth, dropped frames, camera-open latency, segment rollover gap, and recovery behavior.
- Test with the recorder foregrounded, backgrounded, and with preview disabled.
- Record OEM camera takeover and return behavior.
- Store only anonymized metrics in the repository; do not commit footage, vehicle logs, or identifiers.
- Convert the targets above into a vehicle test sheet with exact pass/fail evidence.

Exit gate: the existing 360 pipeline has a reproducible baseline against which front-only can be compared.

### Phase 1 — Add recording mode and fail-closed source identity

Add a persisted model such as:

```kotlin
enum class RecordingMode {
    FRONT_ONLY,
    SURROUND_360,
}
```

Extend recorder configuration and sidecar metadata with backward-compatible defaults:

- recording mode;
- source fingerprint and selected Camera2 ID;
- direct-front or composite-crop source kind;
- calibrated front lane, crop rectangle, and rotation;
- output width, height, frame rate, codec, and requested/actual bitrate;
- calibration version;
- pipeline version.

Replace camera ID `"2"` and first-camera/profile fallback behavior with a source catalog that:

- fingerprints the actual stream and relevant camera characteristics;
- accepts only known or user-confirmed compatible sources;
- rediscovers capabilities after an OTA or source change;
- blocks recording when identity is ambiguous;
- separately probes for a verified direct front stream.

Existing installations must not silently migrate into a different capture mode. On first launch after the change, require mode confirmation and front calibration before front-only recording.

Primary areas: `ProductRecorderConfigFactory.kt`, `CameraProfiles.kt`, `SettingsStore.kt`, `RecorderModels.kt`, and `SegmentSidecar.kt`.

Exit gate: unit tests prove preference and sidecar compatibility for old, missing, corrupt, direct-front, composite, and unknown source data.

### Phase 2 — Build front-view calibration

Add a parked-only calibration flow that:

- displays neutral lane names until calibrated;
- lets the user select the actual front lane;
- lets the user confirm crop bounds and rotation;
- records the source fingerprint and calibration version;
- invalidates calibration when the source geometry or fingerprint changes;
- requires reconfirmation rather than guessing.

Store raw crop geometry independently from preview zoom and fisheye presentation settings. The first front-only release should encode the selected raw front lane; display-only lens correction remains optional and must not change the recorded evidence.

Primary areas: `FourLaneLayout.kt`, `FourLaneProductOverlay.kt`, `FourLaneTextureContainer.kt`, `SettingsScreen.kt`, and `SettingsStore.kt`.

Exit gate: pure layout tests cover all four lanes, rotations, boundary rounding, invalid crops, and calibration invalidation.

### Phase 3 — Stabilize the common recorder lifecycle

Refactor service ownership before adding the new encoder:

- Make the recording pipeline independent of the activity `TextureView`.
- Keep preview disabled by default while recording; attach only a service-safe optional preview output.
- Make start, stop, cleanup, release, and late callbacks idempotent.
- Preserve generation tokens across camera, session, encoder, and segment callbacks.
- Persist terminal failure reason and last-known health instead of resetting immediately to `IDLE` on service destruction.
- Listen for encoder/muxer runtime errors and output-format events.
- Track encoded-frame progress and file growth; use watchdogs to detect a running-but-stalled pipeline.
- Move storage scans, media inspection, sidecar work, and cleanup off the camera thread.
- Respond to Camera2 availability callbacks with one serialized reopen state machine and bounded exponential backoff.
- Keep `START_NOT_STICKY` and explicit start semantics.

Primary areas: `CameraRecordingService.kt`, `RecorderSession.kt`, `RecorderCommands.kt`, `RecorderModels.kt`, `FrameHealth.kt`, and `SafeManualPreviewController.kt`.

Exit gate: lifecycle tests cover duplicate commands, activity recreation, preview loss, stale callbacks, OEM disconnect, repeated errors, service destruction, and recovery cancellation.

### Phase 4 — Implement the front-only pipeline

Create a `RecordingPipeline` abstraction with at least these operations:

- prepare against a verified source and immutable configuration;
- expose required Camera2 output surfaces;
- start only after encoder and capture session are ready;
- report encoded timestamps, errors, and health;
- request a keyframe/rollover where supported;
- stop and release idempotently.

For a verified direct front stream, feed the camera into a surface encoder directly.

For the composite source:

- receive frames on a service-owned `SurfaceTexture`;
- render only the calibrated lane through EGL/OpenGL ES to the encoder input surface;
- use a latest-frame policy rather than queueing frames;
- keep texture transforms, crop math, and rotations explicit and tested;
- release GL objects on all stop/error paths;
- measure GPU, thermal, and memory impact on the target head unit.

Use `MediaCodec` plus `MediaMuxer` for the front-only surface pipeline. Query codec capabilities before selecting resolution, frame rate, bitrate, profile, level, color format, and alignment. Start with H.264 for compatibility and fail with a clear message when no validated profile exists.

Do not silently reduce quality, switch modes, or record the full composite when front encoding fails. A user-selected, validated fallback profile may be offered explicitly.

Exit gate: generated files contain only the calibrated front view, report actual codec parameters, play through the app and an independent player, and meet the provisional storage target.

### Phase 5 — Make segment rollover and recovery durable

Replace best-effort finalization with a recoverable transaction:

1. Write and sync a small segment journal before capture begins.
2. Record to an app-owned partial file.
3. Stop the encoder/muxer and validate basic container/track metadata.
4. Atomically promote the MP4 where the filesystem supports it.
5. Atomically write the sidecar with the same segment identity.
6. Mark the journal complete and remove it only after both files are durable.
7. Reconcile journals, orphan MP4s, sidecars, and partials on the next explicit app/service start.

Fix validation timestamp units and define success from actual output evidence, not only `stop()` returning without an exception. Ensure I/O shutdown waits for queued finalization work within a bounded timeout.

Measure rollover gaps from encoded presentation timestamps. Prefer a pipeline capable of preparing the next muxer/segment boundary without closing and reopening the camera. Request an IDR frame at the boundary when supported.

Primary areas: `RecorderSession.kt`, `SegmentSidecar.kt`, `SegmentNaming.kt`, and `RecordingValidator.kt`.

Exit gate: fault injection at every finalization step produces either a recoverable valid segment or a clearly quarantined artifact, never a healthy-looking orphan.

### Phase 6 — Harden storage and incident protection

Build a serialized recording index covering finalized, partial, quarantined, journaled, protected, and temporarily pinned items.

- Enforce quota and reserve against every app-owned recording artifact, not only normal finalized clips.
- Bound quarantine, event logs, thumbnails, and diagnostics independently.
- Preserve permanent protection separately from upload, playback, and analysis pins.
- Make sidecar read-modify-write operations serialized and merge-safe.
- Select incident clips using encoded segment time ranges, not `lastModified` ordering.
- Persist an incident request before returning success to the UI.
- Protect the configured before/after window deterministically, including an active segment.
- Report remaining recording time from quota, reserve, protected usage, and the measured bitrate.

Primary areas: `StoragePolicy.kt`, `RecorderLibrary.kt`, `IncidentProtection.kt`, `EventLogger.kt`, and `RecordingThumbnailCache.kt`.

Exit gate: concurrent cleanup/protect/playback tests prove protected and pinned content cannot be deleted or lose flags.

### Phase 7 — Deliver a driver-safe product UI

The recording screen should show, at a glance:

- selected mode: Front only or 360;
- recording, paused-by-OEM, recovering, storage-blocked, or failed state;
- verified source/calibration status;
- elapsed time, current segment, health, and trustworthy remaining time;
- a large incident-protect action with unambiguous confirmation.

Settings should provide mode selection, parked-only front calibration, validated quality profiles, retention controls, and an explicit 360 resource/storage warning. Do not place setup or troubleshooting flows where they encourage interaction while driving.

Refresh the events/library view from an observable index rather than only on initial composition or local actions.

Update `README.md`, `README.zh-CN.md`, `SECURITY.md`, and both English/Chinese UI text for the new recording behavior, data lifecycle, and limitations.

Exit gate: rotation, activity recreation, backgrounding, and screen-off do not interrupt recording; mode and failure state remain accurate after returning to the app.

### Phase 8 — Automate validation

Add JVM tests for:

- mode and source selection policies;
- source fingerprint and calibration migration;
- crop/rotation geometry;
- codec profile selection;
- recorder state transitions and stale callbacks;
- journal reconciliation and sidecar compatibility;
- incident-window selection;
- quota, reserve, quarantine bounds, and every pin type;
- encoded-timestamp gap calculations.

Add Android instrumentation or device tests for service/activity recreation, surface loss, MediaCodec/Muxer lifecycle, process death, low storage, and corrupt preferences. Add fault-injection seams instead of relying only on hard-to-reproduce failures.

Required release checks:

```powershell
.\gradlew.bat :app:testReleaseUnitTest
.\gradlew.bat :app:lintRelease
.\gradlew.bat :app:assembleRelease
```

Exit gate: these checks run in CI, with failures blocking release artifacts.

### Phase 9 — Validate on compatible vehicles

Run a documented vehicle matrix for both modes:

- 1-hour, 8-hour, and 24-hour recordings;
- day, night, rain, heat, and cold where practical;
- foreground, background, screen-off, and activity recreation;
- controlled app-process termination and explicit restart;
- OEM camera/AVM takeover and release;
- repeated camera unavailable/disconnect/error sequences;
- nearly full storage and protected-content saturation;
- incident protection near segment boundaries;
- playback of every produced segment in-app and independently;
- thermal throttling, memory pressure, and sustained file-write rate;
- Android Auto or other known contention scenarios;
- baseline comparison with the app stopped and, where feasible, uninstalled.

Stop a test immediately if OEM camera behavior, vehicle UI responsiveness, thermal stability, or another vehicle function degrades.

Exit gate: publish anonymized pass/fail totals and remaining limitations. Do not label the app stable or safe based only on code review, unit tests, emulator runs, or a short drive.

## Milestones and release order

| Milestone | Scope | Release decision |
| --- | --- | --- |
| M1 | Baseline and measurable gates | Internal engineering only |
| M2 | Mode model, verified source identity, sidecar migration | No front recording yet |
| M3 | Calibration and service-owned lifecycle | Internal head-unit build |
| M4 | Direct-front or GPU-crop prototype | Vehicle test only |
| M5 | Durable segment recovery and health monitoring | Limited alpha candidate |
| M6 | Storage and incident hardening | Front-only default candidate |
| M7 | UI, bilingual docs, instrumentation, CI | Public alpha candidate |
| M8 | 24-hour soak and OEM coexistence matrix | Stability claim review |

Do not begin with cosmetic UI work. The critical path is verified source identity, independent service ownership, front-only encoding, crash-safe finalization, and storage safety.

## Definition of done for front-only default

Front-only may become the recommended default only when all of the following are true:

- The front source is positively verified and calibrated.
- No code path silently records 360 when front-only is selected.
- Recording survives activity and preview-surface destruction.
- Produced files contain only the intended front view and pass independent playback checks.
- Codec choice is based on runtime capability and actual parameters are recorded.
- Segment health is based on encoded output progress and validated container metadata.
- Crash recovery reconciles all tested finalization interruption points.
- Storage tests prove that protected and pinned artifacts cannot be evicted.
- The storage-rate, rollover-gap, recovery, endurance, and OEM coexistence targets pass on compatible hardware.
- English and Chinese UI/documentation accurately describe the behavior and remaining risk.

Until these gates pass, releases should continue to describe the application as experimental and should avoid unattended-use or safety-critical claims.
