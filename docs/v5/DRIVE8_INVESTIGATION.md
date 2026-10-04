# Drive8 investigation — drive7 field result

The drive7 field result did not validate continuous cabin recording. Two supplied away reports identify distinct runs in the new process. The detailed report attachment contains only parts 1, 2, 16 and 17 of 17; its full-report hash cannot be checked. No actual old/new codec format was captured by drive7, so copying further old fragments cannot reveal which compared field changed.

## Confirmed sequence

In the first run, surround starts at elapsed 30,831,482 ms. Manual stop occurs 16,040 ms later, with no encoded frames. Camera close is acknowledged at 30,852,303 ms; only afterward, at 30,852,337 ms, does the codec report `ENCODER_TRACK_FORMAT_INCOMPLETE`. This is a separate startup/no-frame and terminal-drain observation. It does not establish why the original input had no frames, or which format field was missing.

In the next run:

| Event | Elapsed ms |
| --- | ---: |
| Cabin first segment starts | 30,871,362 |
| Cabin rejects output format (`OUTPUT_PREPARE`, 1,804 encoded frames) | 30,931,384 |
| Cabin camera close enters | 30,931,532 |
| Last surround source frame, independently reconstructed from three counters | 30,931,549 |
| Cabin camera close returns / acknowledges | 30,931,576 |
| Cabin cleanup settles | 30,931,580 |
| Surround source watchdog fires | 30,946,553 |
| Surround cleanup finally settles | 30,958,598 |

The first failure explicitly belongs to `FRONT_CABIN / CONTINUOUS_CODEC`, 60.022 seconds after segment start. `ENCODER_TRACK_FORMAT_INCOMPATIBLE` means the complete snapshot passed parsing but differed from the installed contract. It does not identify CSD versus color, layout or other compared properties. Surround's 15-second watchdog works: it triggers 15.004 seconds after its last source frame. Slow cleanup is later, not the initial cause. The overlap with cabin close is strong timing evidence, not proof of a vendor Camera2/HAL cause. The report's sleep cycle is older and from another process.

## Correction scope

Drive7 assumed one decoder contract must last for the entire direct-codec recording run. That is unnecessary when a normal file cut can give the next segment its own track. The correction keeps the existing camera and encoder session and applies a complete changed format to the next independent MP4, under explicit conditions:

- A normal file cut is already pending, with its existing callback and watchdog.
- Codec MIME and image layout remain fixed; the full new codec configuration passes validation.
- The first media sample after the change is a keyframe that starts the planned next file. Codec-config buffers and empty EOS buffers are not media samples.
- The new immutable format travels in the same queue boundary as that keyframe. The writer finishes the old file, updates the format for the new file, then uses the existing asynchronous continuation callback.
- Stop, cancellation or failure must not create a new file. Unexpected changes outside a cut, conflicting pending changes, layout changes and non-keyframe transitions remain explicit errors.

Review also identified a stop race: the caller can publish its stop flag before the codec worker runs the cancellation task. A format notification arriving between those steps must not turn normal finalization into a new recording failure. The direct recorder now records that terminal format evidence, cancels any pending transition and discards media belonging to an uncommitted or unreadable terminal format. Compatible trailing samples can still drain; already written samples retain their actual coverage. Errors during active recording remain strict.

This is not a relaxation of CSD equality within an existing MP4 track. The [MediaCodec output-format contract](https://developer.android.com/reference/android/media/MediaCodec#INFO_OUTPUT_FORMAT_CHANGED) assigns subsequent output to the new format; [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer#addTrack(android.media.MediaFormat)) configures tracks before starting each muxer. Rollover is enabled only for normal cabin/IR recording through the direct continuous encoder; other direct roles and the shared-input surround writer retain their strict format policy.

Both report-copy paths gain bounded, per-channel format evidence: fixed field labels, change/error categories, presence and buffer lengths, plus notification decisions. They do not export codec bytes, hashes, arbitrary vendor keys or arbitrary exception text. First, latest and latest meaningful format changes are protected within the bounded retained history. Later channel watchdogs, stop and fresh-start events keep separate session identities instead of borrowing or overwriting an earlier run's format cause. At most six session/channel groups are included, with explicit truncation indicators.

The separate no-frame startup case is not declared fixed. New incomplete-format evidence is intended to distinguish missing CSD, invalid fields and copy failures if it recurs. No native camera close sequence, encoder EOS behavior, keyframe request or vehicle command is changed to guess at that failure.

## Verification

The original strict implementation failed five of eight new rollover regression tests as expected; both initial report-retention regressions also failed before implementation. After the changes and the stop-race correction, the final local run passed 1,562 unit tests: recorder 1,456, drive-map 26 and shared transfer protocol 80, with no failures, errors or skipped tests. Android test sources compiled successfully; no device tests were executed.

Recorder and map lint completed with no fatal/error findings. Existing findings remain: recorder 209 warnings and 21 hints; map 3 warnings. `git diff --check` passed. Final command: `:drive-map:testDebugUnitTest :drive-map:lintDebug :transfer-protocol:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`, with two Gradle workers and the private field/probe flags enabled. The release package identity, signature, archive alignment and artifact digest are checked separately in the delivery's `validation.json`.

No head unit is connected locally. Actual continuous recording, startup recovery, hardware preview and playback still require owner confirmation; passing host tests does not establish any of those results.
