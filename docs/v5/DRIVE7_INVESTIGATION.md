# Drive7 investigation — drive6 stops recording around the first split

Status: secondary handling and diagnostic changes verified locally; first-cause investigation awaits the owner's existing sleep report. This is not a release receipt or confirmation that the first recording failure is fixed.

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

## Confirmed defects addressed independently

1. The shared-input reader kept updating its worker heartbeat while receiving no frames. Its source wait policy allowed thirty minutes of silence, while the pending keyframe-cut deadline was paused. A 15-second source deadline now uses the existing terminal failure and cleanup path. It detects missing footage; it does not repair or silently restart the camera. Brief pauses remain allowed. Time-lapse selection does not affect the incoming source heartbeat.
2. An active but failed floating preview suppresses the local home preview. Re-entering home or choosing the same source did not provide a clear recovery action. Home now offers an explicit retry after real camera, GL, recording-service and cleanup ownership gates permit it. Click-time checks are repeated. No gate is cleared by a timeout and no automatic retry is added.
3. Later cleanup errors could hide the first continuous-recording failure in the short report. The bounded history now protects and separately exports the first available and latest failure per known session/channel. Direct-encoder diagnostics identify cut request, drain, output preparation, writing, finalization, cleanup and publication stages, using typed fields and cached counters. The recording algorithm and native call order are unchanged by this instrumentation.
4. Concurrent channels share session and segment identities, but the global capture cache belongs to the primary channel. Report matching now also requires the channel, so an auxiliary fault cannot borrow surround frame counts. Unknown ownership remains unmatched.

## Remaining boundary

The initial black preview and the first approximately 60-second recording failure are not yet explained by a retained first-cause exception. The owner is preparing the existing sleep report. Do not describe these as fixed merely because the secondary handling and diagnostics pass local tests. Do not change EOS handling, keyframe parameters, camera ownership or native teardown speculatively.

The source watchdog intentionally terminates a recording that has no fresh camera input for 15 seconds. A silent OEM interruption longer than that will therefore need a new recording after cleanup; the separate native contention-callback recovery path remains in place. Hardware behavior and UI rendering still require validation on the head unit.

## Local verification — 2026-10-04

- Source-deadline regression: 11 tests, with 2 expected failures under the original 30-minute policy, then all 11 passing after the change. Logs: `build/drive7-source-red.log` and `build/drive7-source-green.log`.
- Car app unit suite: 1,413 tests, zero failures, errors or skipped tests.
- `:app:compileDebugAndroidTestKotlin` passed. These UI tests were compiled, not executed on a head unit.
- `:app:lintDebug` passed. Full command output: `build/drive7-car-checks.log`.
- No APK was delivered or installed, no vehicle controls were sent, and no public release was modified. The phone app and transfer format are unchanged.
