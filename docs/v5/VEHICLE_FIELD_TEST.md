# Vehicle field test preview

Private Recorder preview: **5.1.0-field1 / 105**. This is a collection tool for owner field testing, not a claim that all signals or three cameras work on this vehicle. Phone and public releases are unchanged.

Build with `-PvehicleFieldTest=true -PcameraConcurrencyProbe=true`. Both experimental entries default to off without these properties. The existing optional GPS recording sidecar remains separate; the field check does not change recorder source selection or burn telemetry into video.

## One test, one JSON

1. While parked, open **Settings → Vehicle field test**.
2. Select **Quick · 2 min** for stationary checks, or **Road · 30 min** before a drive. Confirm parked. Grant location permission if GPS is wanted, or uncheck GPS.
3. Tap **Start manual check**. Return to ordinary OpenAVM recording if desired. The check does not start recording. Do not operate the test screen while driving.
4. After parking, return and tap **Stop now**. Screen-off and the time limit also stop observation. There is no restart at boot, automatic return, wake lock or keep-alive mechanism.
5. Optionally run the separate two/three-camera preview experiment while parked. This still needs its own manual start and checks ownership before opening cameras. Stop ordinary recording first.
6. Return to **Vehicle field test → Copy combined JSON** (or **Save JSON**). The bundle includes recent field checks and camera tests, so one JSON can be shared for diagnosis.

Coordinates are excluded by default. Opting in includes valid, fresh latitude/longitude in that run's report and timeline. Earlier opted-in runs remain opted in when included in a later combined export. Reports stay in private no-backup app storage until manually exported. No account credentials, VIN, photographs or video are included. Clipboard content is shared only when the user copies it.

## What is collected

- 61 explicitly allowlisted ECARX getters: gear/speed, ignition, pedals/steering, lighting, doors/windows/locks, seats/belts, cabin/outdoor temperature, battery/range/odometer, stock surround-view and Sentry indicators. No arbitrary ID scan, setter, control command or SDK subscription.
- Exact getter availability, sanitized failure class, raw values, decoder status, observed ranges, returned/valid/change counts and read latency. Unknown enums and sentinel values are preserved as diagnostic raw values, not accepted as vehicle states.
- GPS/network/fused provider callbacks, fix age, accuracy, mock/cache flags, valid-position/speed counts, first valid fix delay, and independently age-gated GPS speed. A location without speed cannot erase another provider's fresh speed.
- Recorder-reported state and segment/camera context. This is not independent proof of correct saved video or playback.
- Monotonic elapsed timing, manual observation labels, sampling gaps, stop reason, pending SDK call and per-signal changes. User labels are not ground truth.

Fast getters target 250 ms; others target 3 seconds. One serial worker prevents overlapping requests. A ten-second stalled batch stops the test and leaves new starts blocked until the worker actually returns; no replacement thread or repeated SDK creation is used to bypass the stall. A blocked call cannot be forcibly cancelled. Other reads are cancelled between calls, with late callbacks fenced by run identity.

**A successful getter call is not proof of a newly measured sensor value.** Report ages refer to getter completion unless the source supplies its own timestamp (Android Location). An unchanging value can be correct or cached. The tool records evidence for later comparison, not a universal freshness certification.

## Bounded evidence and recovery

- Periodic private snapshots every 15 seconds, plus final snapshot; latest four vehicle sessions retained. A process kill can leave `complete=false` with up to roughly 15 seconds absent. No silent restart follows.
- Five-second timeline, at most 361 entries; first two and latest eight raw changes per signal, with omitted counts; up to 12 distinct raw examples and 24 manual markers. Fast analog signals cannot consume another signal's event allowance.
- Latest eight camera reports retained, replacing the same run's report when cleanup is later confirmed. A common I/O lock protects camera AtomicFile reads/writes.
- Combined JSON targets 240 KiB UTF-8. Older timelines/change detail are omitted first; if needed, the latest timeline is sampled every sixth point while retaining both endpoints, then change detail/history is reduced. `trimLevel` and `exportTimelineStride` state reductions. Summary counters remain unless even a minimal bundle is too large, in which case a structured export error is returned.
- Unsupported SDK or denied GPS permissions do not turn into invented zero values. No fix after 30 seconds is visible even if cached/invalid samples were delivered.

## Reference and confidence

The fixed getter IDs/kinds/zones were compared against the public [Zeekr Shortcut v2.0.0 source](https://github.com/dts88/zeekr-shortcut-car/tree/6379201003a786f1601e5a99990703d3844b5598). The adapter is independently implemented. `reference_observed`, `provisional`, and `unverified` describe the reference's confidence; none validates this head unit. Steering and pedal scale remain explicitly provisional raw values. Stock-360 state is observed only and never used as authority to open/close cameras.

JVM tests cover getter routing/decoding, invalid and missing values, GPS quality/privacy, independent speed freshness, per-signal change retention, completed-run callback fencing and JSON budgeting. Synthetic Android UI tests are supplied for English/Chinese layouts and manual admission/export gates. A compiled test is not an executed UI test: see the delivery validation file for executed checks and hardware limitations. Vehicle access, real GPS, camera concurrency and on-vehicle UI remain owner field-test items.
