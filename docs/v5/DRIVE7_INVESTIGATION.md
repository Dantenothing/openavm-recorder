# Drive7 investigation — drive6 stops recording around the first split

Status: supplied detailed log fragments identify the application guard that stops cabin recording at the first split. The compatible-format correction and prior secondary handling are implemented in the private drive7 candidate. Device validation remains necessary.

## Owner observation

On drive6, the home preview initially stayed black. After several attempts it worked. Recording then stopped producing footage after roughly one minute; the library also contained only that minute. The owner confirmed that cabin recording was enabled and IR recording was disabled. No further drive is needed to obtain the requested existing sleep report.

## Evidence from the supplied short report

All times below are elapsed milliseconds from the device report, not wall-clock estimates.

| Event | Elapsed time | Interpretation |
| --- | ---: | --- |
| First and second channel start events | 9,611,533 / 9,612,211 | Old events do not identify the channel. |
| Continuous encoder failure | 9,672,236 | 60,025 ms after the second start. |
| Device closed during that cleanup | 9,672,634 | Cleanup settled at 9,672,636. |
| Last surround write | 9,672,662 | About 61 seconds of surround footage; close timing is correlated, not causal proof. |
| Next requested cut | 9,731,788 | No fresh source/keyframe reached the writer. |
| Later manual stop | 10,246,114 | 573,452 ms after the last write. |
| Close marked unconfirmed | 10,253,119 | Later cleanup failure, not the original recording failure. |
| Late cleanup settled / stop acknowledged | 10,255,410 / 10,255,444 | Resources eventually acknowledged release. |
| Preview first-frame timeout | 10,275,534 | Reopened preview still received no first frame. |

An earlier run in the same supplied history failed 60,042 ms after its second start. That makes the first cabin/direct-encoder file split a strong investigation lead. The report does not retain the original exception, stage and channel sufficiently to prove the cause. It also does not prove that closing cabin Camera 1 caused surround Camera 2 to stop. Separate camera ownership references are used in the code.

The contradictory boot-count/elapsed-time evidence remains `CLOCK_CONFLICT`; it does not establish why the ZEEKR logo appeared.

## Additional sleep report received — 2026-10-04

The owner supplied a drive6 / code 111 `OPENAVM_AWAY_SHORT_JSON` containing a later recording session, rather than the earlier session listed above. Its last recorded start is at 22,925,124 ms. The first continuous fault is at 22,985,395 ms, **60,271 ms later**. It records `LEGACY_CONTINUOUS`, `UNCLASSIFIED_THROWABLE`, exact exception type `java.lang.IllegalStateException`, and no cached failure counters.

In the shipped drive6 source (`7968390`), the only production site that writes `LEGACY_CONTINUOUS` is `ContinuousVideoRecorder.fail`. The shared-input primary uses `ProductContinuousRecorder`. Combined with the owner's cabin-only auxiliary setting, the primary still reporting `SURROUND / segment 2`, and the distinct camera owners, this identifies the initial failing recorder as the cabin/direct continuous path. It does not identify which operation inside its broad catch failed.

That recorder requests close at 22,985,399 ms and settles at 22,985,581 ms. Two subsequent primary power snapshots independently place the last surround capture at **22,985,580 ms**. The near-identical timing strengthens the correlation between auxiliary cleanup and primary source loss; it is still not proof that a HAL camera-close operation caused the loss.

The manual primary stop follows the first fault by 9,134 ms. Its close is marked unconfirmed at 23,001,534 ms, then actually settles at 23,003,879 ms (9,349 ms after the close request). There are no unsettled owners at export. The original recording fault therefore precedes this cleanup timeout. The reported mirror wake/automatic-start cycle is around 20,398 seconds earlier; it must not be treated as the current recording's failure trigger. Screen observations around the fault remain on.

`IllegalStateException` alone does not distinguish a sync-frame request, queue/target state check, codec drain, muxer write, stop or release. Do not assert a muxer/EOS root cause. The existing **Settings → Recording diagnostics → Prepare detailed report → Copy full JSON** path reads recent recorder history and preserves up to 400 characters of the original event `errorMessage`. The relevant `RECORDER_CONTINUOUS_FAILED` event is at elapsed 22,985,395 ms. It may retain `CONTINUOUS_ENCODER_FAILED:<original message>` without another recording. No caught-exception stack trace was persisted by this old path, so a full stack cannot be promised.

## Detailed fragments identify the application guard

The next attachment contains parts **2–5 of 17**, all with the same declared report hash. Four complete `RECORDER_CONTINUOUS_FAILED` events can be parsed from these contiguous fragments. Every one contains `CONTINUOUS_ENCODER_FAILED:ENCODER_FORMAT_CHANGED_MID_RUN`. The full-report hash cannot be verified without all parts; the complete individual event records are sufficient to identify this error and no further copying was requested.

The preceding cabin segment start to failure intervals are **60,015 / 60,042 / 60,025 / 60,015 ms**. These are time/configuration matches to Camera 1, 640×480, internal storage and 60-second segments; old start events do not support a strict channel/session-ID join.

In drive6, `ContinuousVideoRecorder` unconditionally checks `writerFormat == null` for every `INFO_OUTPUT_FORMAT_CHANGED`. The second notification therefore throws this exact application error regardless of whether the actual video track changed. The shared-input backend contains the equivalent `PRODUCT_CODEC_FORMAT_CHANGED_TWICE` check. This confirms the immediate software stop trigger. The old logs did not save both formats, so they do not prove that the device's second format was compatible.

The correction compares immutable decoder configuration, retains the established muxer track for compatible repeats, and rejects unreadable or incompatible formats before accepting subsequent samples. It compares the AVC SPS/PPS bytes and stable video properties, rather than runtime bitrate or vendor counters. The original format remains available for subsequent files, and each muxer receives an independent copy. This follows the [MediaCodec output-format contract](https://developer.android.com/reference/kotlin/android/media/MediaCodec#INFO_OUTPUT_FORMAT_CHANGED) and the [MediaMuxer track lifecycle](https://developer.android.com/reference/android/media/MediaMuxer#addTrack(android.media.MediaFormat)); it does not add a track to an already started muxer.

The short-report parser also recovers an allowlisted specific code from a single `CONTINUOUS_ENCODER_FAILED:` wrapper when the old typed evidence is unclassified. Raw error messages are not exported. This makes the known failure visible without a detailed multi-part report.

## Confirmed defects addressed independently

1. The shared-input reader kept updating its worker heartbeat while receiving no frames. Its source wait policy allowed thirty minutes of silence, while the pending keyframe-cut deadline was paused. A 15-second source deadline now uses the existing terminal failure and cleanup path. It detects missing footage; it does not repair or silently restart the camera. Brief pauses remain allowed. Time-lapse selection does not affect the incoming source heartbeat.
2. An active but failed floating preview suppresses the local home preview. Re-entering home or choosing the same source did not provide a clear recovery action. Home now offers an explicit retry after real camera, GL, recording-service and cleanup ownership gates permit it. Click-time checks are repeated. No gate is cleared by a timeout and no automatic retry is added.
3. Later cleanup errors could hide the first continuous-recording failure in the short report. The bounded history now protects and separately exports the first available and latest failure per known session/channel. Direct-encoder diagnostics identify cut request, drain, output preparation, writing, finalization, cleanup and publication stages, using typed fields and cached counters. The recording algorithm and native call order are unchanged by this instrumentation.
4. Concurrent channels share session and segment identities, but the global capture cache belongs to the primary channel. Report matching now also requires the channel, so an auxiliary fault cannot borrow surround frame counts. Unknown ownership remains unmatched.

## Remaining boundary

The initial black preview, actual contents of the device's repeated output format, and any HAL interaction between closing cabin and surround cameras still require head-unit confirmation. The unconditional second-format guard is identified and corrected; uninterrupted real-camera recording cannot be claimed from unit tests alone. EOS handling, keyframe requests, camera ownership and native teardown remain unchanged.

The source watchdog intentionally terminates a recording that has no fresh camera input for 15 seconds. A silent OEM interruption longer than that will therefore need a new recording after cleanup; the separate native contention-callback recovery path remains in place. Hardware behavior and UI rendering still require validation on the head unit.

## Local verification — 2026-10-04

- Source-deadline regression: 11 tests, with 2 expected failures under the original 30-minute policy, then all 11 passing after the change. Logs: `build/drive7-source-red.log` and `build/drive7-source-green.log`.
- Codec-format regression: the original unconditional second-format guard produced 6 expected failures among 9 tests; after the fix and one added hint-history case, all 10 passed. Logs: `build/drive7-format-red.log` and `build/drive7-format-green.log`.
- Final car app unit suite: 1,426 tests; map module: 26; shared transfer protocol: 80. All 1,532 passed, with zero errors or skipped tests.
- `:app:compileDebugAndroidTestKotlin` passed, including the new synthetic MediaFormat/CSD buffer isolation test. Android tests were compiled, not executed on a device. An initial missing test annotation dependency was corrected using the project's existing JUnit SDK assumption approach.
- App and map lint passed with zero errors; existing warnings remain (209 app warnings plus 21 hints, 3 map warnings). Final command output: `build/drive7-car-final-tests.log`.
- No head unit was connected, no vehicle controls were sent, and no public release was modified. The phone app and transfer format are unchanged. APK packaging and signing verification are recorded separately in the delivery folder's `validation.json`.
