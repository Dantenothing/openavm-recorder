# Security and safety reporting

AVM Recorder is experimental vehicle-adjacent software. Reports about security, privacy, excessive resource use and unexpected interaction with vehicle systems are all in scope.

## Supported versions

Only the latest public alpha release is currently supported. Older test APKs and the retired Capability Lab experiments are not supported.

## What to report

Please open a GitHub Issue if you observe any of the following:

- a security vulnerability;
- unexpected network activity or permission use;
- excessive CPU, GPU, memory, storage I/O or thermal impact;
- recording corruption or uncontrolled storage growth;
- interference with the factory 360°, reverse camera or infotainment system;
- a privacy issue; or
- behaviour that could distract a driver or affect safe vehicle operation.

Include only the minimum non-sensitive information needed to understand the report:

- AVM Recorder version;
- vehicle model, region and head-unit software version;
- whether the vehicle was parked;
- concise reproduction steps;
- expected and observed behaviour; and
- whether stopping or uninstalling the app restored normal behaviour.

## Do not publish sensitive vehicle data

Do not post any of the following in a public Issue:

- VIN;
- precise GPS location or trip history;
- number plates or identifiable faces;
- personal account or contact information;
- tokens, credentials or private keys;
- complete vehicle logs; or
- unredacted recordings or screenshots.

If a report requires exploit details or sensitive evidence, open an Issue containing only a non-sensitive summary and state that additional details are available privately. Wait for the maintainer to arrange an appropriate private exchange before sending them.

## Immediate safety response

If AVM Recorder appears to affect a factory camera, reverse-view function, infotainment stability or another vehicle system:

1. stop recording;
2. close or uninstall the app when it is safe to do so;
3. confirm that the factory function has recovered; and
4. report the observation without interacting with the app while driving.

Do not rely on AVM Recorder as a safety system or as the only record of a trip or incident.

Front-only recording does not establish isolation from factory camera, AVM, ADAS, ISP, memory, GPU, encoder, thermal or storage resources. If source identity or parked calibration is no longer an exact match, do not bypass the recording block; reconfirm only while parked. Stop testing immediately if the GPU crop path, encoder, camera recovery or sustained writes coincide with degraded factory-camera behaviour, vehicle UI responsiveness or thermal stability.

## Response expectations

This is a small experimental project and does not currently provide a guaranteed response or remediation time. Safety-relevant and reproducible reports will be prioritised.
