# V5.1 release verification

14 October 2026, Australia/Adelaide. Recorder 5.1.0 / 134 and Phone 5.1.0 / 56.

## Release scope

Recorder promotes the behavior of the latest delivered support4.133 candidate. RETAINED_CAMERA_HANDOFF_ENABLED is true independently of RECORDER_SUPPORT_ENABLED, which is false for the public build. Private remote diagnostic activities/services, update receivers and installation permissions are omitted. Existing local diagnostic export remains available. Recorder algorithms and camera request/encoder settings are unchanged from that candidate apart from this flag separation; API guards in the private updater were also corrected during lint review.

The owner reported satisfactory normal use on 14 October and requested a regular release. The message did not specify the exact binary hash, duration, storage or all scenarios. The latest supplied candidate was support4.133. This feedback is not an independent test of the final release binaries or every firmware.

## Automated checks

| Area | Cases | Result |
| --- | ---: | --- |
| Recorder release unit tests | 1,568 | Passed |
| Shared map | 30 | Passed |
| Transfer protocol | 80 | Passed |
| Sound tools | 44 | Passed |
| Localisation | 10 | Passed |
| Unified phone release unit tests | 335 | Passed |
| Phone media/transfer companion release unit tests | 308 | Passed |
| **Total distinct unit tests** | **2,375** | **No failures, errors or skips** |

Recorder Android checks: 10 cases passed without skips, using public feature flags. Coverage includes six-language product tabs/settings/tutorial/installation choices; actual emulator Camera2 preview/record/stop/preview handoffs, retained primary plus cabin admission and playable output; delayed cleanup recovery and cancellation; three-player session playback and direct/automatic segment transitions. Two UI cases were rerun for clean visual inspection.

Phone Android checks: 20 distinct cases passed without skips on an isolated QA installation. These cover missing/imported configuration, local media features, encrypted authenticated transfer, identity/fingerprint checks, authorisation and revocation, embedded video playback, optional setup in English/Chinese and unconfigured widgets. The first run passed 19 cases; one playback test used the old library-card title. It was updated to the current camera-count title and passed on rerun, including actual play/pause after transfer. No production change was needed for this test repair.

Full release lint passed for both projects: zero errors. Recorder retains 210 warnings and 21 hints; Phone retains 95 warnings. These include existing platform/deprecation/localisation recommendations and are not represented as a warning-free result. Six initial API-compatibility errors in the private support updater were fixed with explicit Android-version guards and lint was rerun successfully.

The emulator's System UI service displayed a startup ANR before the app tests. Its event identified com.android.systemui, not OpenAVM. The stale dialog was dismissed and playback/setup visual checks were rerun successfully. This is not counted as a vehicle test or as proof of all-device performance.

## Privacy and artifact boundaries

The public phone build does not consume private protocol configuration. Changed source and newly reachable unpublished history were compared locally with the six private reference values; none matched. No values were printed or included in this report. The final release artifact checks and SHA-256 values are recorded in [RELEASE_VERIFICATION.json](../../RELEASE_VERIFICATION.json) and the release checksums.

Source includes protocol implementation and an empty template. It does not supply manufacturer parameters, accounts, login tokens or signing private keys. Local pairing and media do not require cloud configuration; import alone must not query/control the vehicle.

## Limits retained in the release notes

These tests do not certify proprietary full-resolution camera encoding, physical USB behavior, GPS/vehicle-signal availability, every leave/return cycle or hardware frame synchronisation. The transient initial preview stall and earlier non-increasing encoder timestamp fault have not each been independently established as eliminated. The positive owner feedback does not erase those limits. USB discovery issue #28 remains open. No real cloud commands, private remote vehicle tests or physical-phone installation were performed during release preparation.
