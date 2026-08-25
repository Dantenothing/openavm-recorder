# Front-first vehicle validation sheet

This sheet records anonymized pass/fail evidence. Do not commit footage, VINs, registration plates, precise locations, full vehicle logs or other identifiers. JVM, emulator and build results do not count as vehicle validation.

Stop immediately if a factory camera/AVM function, vehicle UI responsiveness, thermal stability or another vehicle function degrades.

## Test identity

- App commit/build:
- Date and ambient conditions:
- Anonymized vehicle/head-unit class:
- Head-unit software/OTA fingerprint (non-identifying):
- Recording mode and selected profile:
- Confirmed source fingerprint prefix:
- Calibration version/front lane/rotation:
- Tester and evidence location (private; do not commit sensitive data):

## Repeatable baseline

Run the same route or parked workload with the app stopped, legacy/360 recording, and front-only recording. Record average, peak and measurement method for each item.

| Measurement | App stopped | 360° | Front only | Pass/fail / notes |
| --- | ---: | ---: | ---: | --- |
| CPU |  |  |  |  |
| Process/system memory |  |  |  |  |
| GPU observation |  |  |  |  |
| Thermal state/temperature |  |  |  |  |
| File growth MB/min |  |  |  | Front target ≤ 4 GB/hour |
| Encoded/capture frame progress |  |  |  | No 15 s stall |
| Camera-open latency |  |  |  |  |
| Rollover gap from encoded PTS |  |  |  | ≤ 1 s; target < 250 ms |
| Camera-return delay |  |  |  | First attempt ≤ 5 s |

Repeat each recording case foregrounded, backgrounded, screen-off and after activity recreation. Confirm that destroying the activity preview does not interrupt the service-owned recording path.

## Output evidence

- Front-only MP4 contains only the calibrated raw front view: ☐
- Front-only output is `1280×1280` H.264 at the sidecar-reported actual profile: ☐
- Every selected segment plays in-app: ☐
- Every selected segment plays in an independent player: ☐
- Sidecar mode, source fingerprint, crop, rotation, requested/actual bitrate, codec and pipeline version match: ☐
- Interrupted finalization produces a recovered valid pair or quarantined evidence, never a healthy orphan: ☐

## Endurance matrix

| Duration/condition | Front only | 360° | Crash/deadlock | Thermal shutdown | Runaway storage | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1 hour |  |  |  |  |  |  |
| 8 hours |  |  |  |  |  |  |
| 24 hours |  |  |  |  |  |  |
| Day/night/rain |  |  |  |  |  |  |
| Practical heat/cold |  |  |  |  |  |  |
| Android Auto/contention |  |  |  |  |  |  |
| Nearly full storage |  |  |  |  |  |  |
| Protected-content saturation |  |  |  |  |  |  |

## OEM takeover and return

Run 200 controlled factory-camera takeover/return cycles. Record a row per batch and retain anonymized event totals.

| Cycle range | OEM takeover succeeded | Recorder paused/released | Serialized reopen only | Recorder recovered | Output healthy | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1–25 |  |  |  |  |  |  |
| 26–50 |  |  |  |  |  |  |
| 51–75 |  |  |  |  |  |  |
| 76–100 |  |  |  |  |  |  |
| 101–125 |  |  |  |  |  |  |
| 126–150 |  |  |  |  |  |  |
| 151–175 |  |  |  |  |  |  |
| 176–200 |  |  |  |  |  |  |

## Release decision

Front-only must not be described as the stable/default vehicle recorder until the output, storage, rollover, recovery, 24-hour endurance and 200-cycle OEM coexistence gates all pass. Record anonymized pass/fail totals and every remaining limitation; do not convert an unrun cell into a pass.
