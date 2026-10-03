# Approved goal: one complete OpenAVM driving-test candidate

Status: **approved by the owner on 2026-10-03; implemented as the private `5.1.0-drive4` candidate**. The owner also requested HUD during live recording and playback on both devices. See `DRIVE4_IMPLEMENTATION_AUDIT.md`, `DRIVE4_TEST_GUIDE.md` and the delivery's `validation.json` for implementation coverage, executed checks and hardware acceptance still pending.
Baseline: `5.1.0-drive3`, commit `6a0d738`, on both Recorder and the unified Phone app.

## Objective

Deliver one coherent pair of upgradeable Android APKs that supports normal-speed, three-camera recording with GPS and vehicle observations, a common playback timeline, synchronized road-map and event review on both devices, live-recording and playback HUD, whole-session paired transfer, bounded cross-segment export, recording-health feedback, and one combined diagnostic JSON. Complete implementation and all checks available in the development environment before asking the owner to install or test again. The owner then performs one comprehensive driving test and copies the final report.

Completion means **ready for that integrated hardware acceptance test**, not a claim that unobserved hardware behaviour has passed. Do not promise a completion time or a real-device synchronization accuracy that has not been measured.

## Required scope

### 1. Recording clock and data alignment

- Extend the existing sidecar/session model instead of creating an unrelated second timeline. Record the relationship between encoded presentation timestamps and the recording monotonic clock, including the anchor source and quality.
- Preserve original GPS and vehicle observation times. A vehicle getter return is an observation time, not necessarily the ECU's measurement time; uncertainty must remain visible.
- Test first-frame delay, keyframe-based file rotation, seeks, missing segments, pauses, clock resets and wall-clock changes. Old recordings retain their existing approximate mapping rather than being relabelled precise.
- All camera channels, maps and events refer to one session time. Overlapping camera files are parallel channels, not sequential files whose durations can be added together.

### 2. Three simultaneous recording channels

- Retain the established surround recording path; add optional front/rear cabin channels using bounded, conservative configurations based on the observed camera capabilities.
- Provide a clear choice and actual status for each channel: starting, receiving/writing frames, unavailable, interrupted, or stopped. Preview and a start request must never masquerade as saved recording.
- Bound queues, encoding work, disk usage, start/stop timeouts and resource ownership. Auxiliary-channel failure must not block the stop button or cleanup. Prioritize preserving surround recording; if it cannot continue, report the actual failure.
- Integrate the selected channel configuration with the existing return/preview/automatic-recording choices. Avoid duplicate starts, hidden short recordings and stale camera ownership.

### 3. Whole-session library, transfer and dual-device playback

- Group the parallel recordings under one session with channel/segment availability, including partial sessions and missing files.
- Support a single session selection for paired transfer, with explicit channel/data selection, resume, cancellation and duplicate-request handling. Preserve existing authentication, TLS, bounds, integrity validation and atomic publication.
- On both devices, provide synchronized channel switching and bounded overview playback. The current video, map and data must preserve the same session position when changing channel or crossing a segment boundary.
- Preserve existing videos, USB playback, pairing, Trash/restore and the ordinary single-camera workflow. Missing channels remain missing; they must not borrow a different channel's or session's frames.

### 4. Maps and event timeline

- Finish integration of the existing real street maps, local fallback, follow/recenter and point-to-video seeking with the new common session timeline.
- Add seekable events based on recorded manual/emergency markers, turn/brake transitions and recording interruptions. Use recorded transitions, not invented accident or harsh-driving classifications.
- GPS, camera or vehicle-data gaps remain explicit. Do not infer a continuous route, a zero speed, or a control state from missing observations.
- Keep video and playback controls accessible with the map open, on car/phone layouts and in English/Simplified Chinese.

### 5. Practical export, recording health and session summary

- Allow a selected time interval spanning compatible normal-recording segments to export as a playable MP4 from a chosen camera. Reject or explain incompatible combinations; do not silently omit selected parts.
- Ordinary video export remains separate from coordinates. Existing explicit GPX export remains scoped to the chosen playback range.
- Show actual per-channel recording progress, storage destination/capacity, interrupted or incomplete files and useful next actions. Distinguish requested recording from observed frame/file progress.
- Summarize recorded duration, available distance and data coverage using retained observations. Distance excludes gaps, and incomplete coverage is labelled.

### 6. One combined test report

- Provide one final report-copy entry on the phone that combines a bounded recorder snapshot from the paired test session with phone transfer, playback and map outcomes. Recorder-only copying remains available.
- Reuse the authenticated paired transport; bind the snapshot to the relevant session. A disconnected or untested side must be labelled absent/not tested, not treated as success.
- Include versions, anonymized session correlation, per-channel startup/frame/file/cleanup outcomes, timing evidence and uncertainty, GPS/vehicle-data coverage, transfer integrity, playback/seek and map outcomes, and bounded error categories.
- Default report excludes coordinates, addresses, VIN/account identifiers, credentials, private paths and video. Keep it bounded and practical to paste. Raw route export remains a separate explicit action.

### 7. Live and recorded HUD

- Add a compact, dismissible HUD to the car recording/overlay views and both devices' video playback. Show speed, gear, turn indicators and brake state where fresh observations exist; distinguish vehicle speed from a labelled GPS fallback.
- Make HUD visibility a user choice. Reuse the recording telemetry lifecycle instead of starting a separate perpetual poller or keeping the car awake for the overlay. Recorded HUD follows the selected session/playback clock, not current live vehicle state.
- Capture the allowlisted accelerator/brake-depth candidates with their raw status. Percentage bars require verified units/range for the actual source; provisional raw values remain clearly unverified and cannot masquerade as percentages. Brake pressed is a binary state, not brake depth.
- Unknown, stale and unsupported values display as unavailable rather than zero/off. Include bounded pedal-source/availability evidence in the combined diagnostic report for the owner's single test.
- Keep video, controls and map usable at narrow sizes and in both languages. HUD is an app display layer; burning it into exported video remains deferred.

## Completion gates

1. Every required user flow above is implemented and connected to the actual production entry points on both apps, not just a probe or separate demo screen.
2. Relevant automated tests cover normal operation and fault cases: timing translation, concurrent ownership, missing channels/data, bounded queues, rotation, cancellation/retry, export selection and report redaction. Use synthetic inputs and fixtures where hardware is unavailable; state their scope honestly.
3. Both app builds succeed; required unit checks and static checks pass. Device-only tests are compiled/prepared and clearly marked unexecuted if no suitable device is available. Do not launch the previously unstable emulator setup merely to obtain a screenshot.
4. Final APKs retain the intended package IDs/signers and increment version codes. Verify packaged contents, upgrade compatibility assumptions, native libraries and absence of embedded real Zeekr protocol parameters.
5. Deliver both APKs together, an updated concise bilingual guide, a single integrated test checklist, checksum/validation receipts and the report-copy entry. No intermediate owner testing is required to continue normal development.
6. Preserve a reliable single-camera path. A known unresolved blocker in the required implementation must be reported as incomplete; do not silently remove it from scope or claim hardware validation from unit tests.

## Deferred scope

Time-lapse map/multi-camera synchronization; downloadable offline city maps; navigation or road snapping; automatic accident classification; three-camera composited export; speed/map overlays burned into exported pixels; complex transcoding; cloud route upload and long-term cross-device history synchronization.

## Execution boundary after approval

Proceed autonomously through implementation, review, fixes and verification until these gates are met. Make routine technical choices without repeatedly asking the owner. Do not stop merely because one subfeature is finished. Owner approval has activated this goal.

This is a private candidate delivery. GitHub publication, operating the owner's real vehicle, and declaring the driving test passed are not part of this goal. Tomorrow's owner test determines sustained three-camera encoding, camera timestamp quality, actual GPS performance, thermal/storage behaviour and vehicle sleep/return behaviour on the target hardware.
