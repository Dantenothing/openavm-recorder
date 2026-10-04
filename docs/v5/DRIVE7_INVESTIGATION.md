# Drive7 investigation — drive6 stops recording around the first split

Status: secondary handling and diagnostic changes verified locally; the received sleep report identifies the direct-recorder failure path, but not the exact failing operation. Existing detailed recording logs are the next evidence source. This is not a release receipt or confirmation that the first recording failure is fixed.

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

## Confirmed defects addressed independently

1. The shared-input reader kept updating its worker heartbeat while receiving no frames. Its source wait policy allowed thirty minutes of silence, while the pending keyframe-cut deadline was paused. A 15-second source deadline now uses the existing terminal failure and cleanup path. It detects missing footage; it does not repair or silently restart the camera. Brief pauses remain allowed. Time-lapse selection does not affect the incoming source heartbeat.
2. An active but failed floating preview suppresses the local home preview. Re-entering home or choosing the same source did not provide a clear recovery action. Home now offers an explicit retry after real camera, GL, recording-service and cleanup ownership gates permit it. Click-time checks are repeated. No gate is cleared by a timeout and no automatic retry is added.
3. Later cleanup errors could hide the first continuous-recording failure in the short report. The bounded history now protects and separately exports the first available and latest failure per known session/channel. Direct-encoder diagnostics identify cut request, drain, output preparation, writing, finalization, cleanup and publication stages, using typed fields and cached counters. The recording algorithm and native call order are unchanged by this instrumentation.
4. Concurrent channels share session and segment identities, but the global capture cache belongs to the primary channel. Report matching now also requires the channel, so an auxiliary fault cannot borrow surround frame counts. Unknown ownership remains unmatched.

## Remaining boundary

The initial black preview and the precise operation behind the approximately 60-second recording failure remain unresolved. The sleep report establishes the failing recorder and exception class; the existing detailed report may recover the original error message. Do not describe these as fixed merely because the secondary handling and diagnostics pass local tests. Do not change EOS handling, keyframe parameters, camera ownership or native teardown speculatively.

The source watchdog intentionally terminates a recording that has no fresh camera input for 15 seconds. A silent OEM interruption longer than that will therefore need a new recording after cleanup; the separate native contention-callback recovery path remains in place. Hardware behavior and UI rendering still require validation on the head unit.

## Local verification — 2026-10-04

- Source-deadline regression: 11 tests, with 2 expected failures under the original 30-minute policy, then all 11 passing after the change. Logs: `build/drive7-source-red.log` and `build/drive7-source-green.log`.
- Car app unit suite: 1,413 tests, zero failures, errors or skipped tests.
- `:app:compileDebugAndroidTestKotlin` passed. These UI tests were compiled, not executed on a head unit.
- `:app:lintDebug` passed. Full command output: `build/drive7-car-checks.log`.
- No APK was delivered or installed, no vehicle controls were sent, and no public release was modified. The phone app and transfer format are unchanged.
