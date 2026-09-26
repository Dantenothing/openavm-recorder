# Security and safety reporting

OpenAVM includes the vehicle Recorder and the Android phone app. Reports about security, privacy, excessive resource use and unexpected interaction with vehicle systems are all in scope. A regular release does not make OpenAVM a safety system or establish compatibility with every vehicle and firmware.

## Supported versions

The [current V5 release](https://github.com/Dantenothing/openavm-recorder/releases/tag/v5.0.1) contains two separately versioned apps:

| App | Supported release |
| --- | --- |
| Vehicle Recorder | **5.0.0 / 102** |
| Android Phone | **5.0.1 / 52** |

The 5.0.1 update changes only the phone. Existing Recorder 5.0.0 users do not need to reinstall the vehicle app. V4 and older releases are superseded; fixes target the current releases. The withdrawn Phone 5.0.0 package and older test APKs are not supported.

For local recording transfer, upgrade both ends from V4 and complete secure pairing again, comparing the complete phone fingerprint. Existing V5 pairing is retained by the Phone 5.0.1 update. See the [upgrade guide](docs/v5/GETTING_STARTED.md).

Phone 5.0.1 does not include or automatically download manufacturer cloud connection parameters. Optional cloud controls require user-supplied configuration and an authorised account. This is separate from local Recorder pairing and media transfer, which need neither cloud configuration nor a ZEEKR login.

## Report vulnerabilities privately

Use GitHub's [private vulnerability reporting form](https://github.com/Dantenothing/openavm-recorder/security/advisories/new) for vulnerabilities, exploit details or sensitive evidence. Private vulnerability reporting is enabled for this repository.

Describe the affected app and version, prerequisites, impact and minimal reproduction steps. Use synthetic or redacted examples where possible. Do not include live account tokens, passwords, connection parameters, pairing codes, private keys or unrelated personal footage. A private report is not a request to share those secrets.

If you cannot use the private form, open a public Issue containing only a non-sensitive summary and say that private details are available. Wait for the maintainer to arrange a private exchange before sending them.

## What to report

The following are in scope. Use private reporting for vulnerabilities or sensitive evidence; ordinary bugs and non-sensitive safety observations can be reported in a GitHub Issue:

- a security vulnerability;
- unexpected network activity or permission use;
- excessive CPU, GPU, memory, storage I/O or thermal impact;
- recording corruption or uncontrolled storage growth;
- interference with the factory 360°, reverse camera or infotainment system;
- a privacy issue; or
- behaviour that could distract a driver or affect safe vehicle operation.

Include only the minimum non-sensitive information needed to understand the report:

- affected app and version, plus both app versions for pairing or transfer issues;
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
- passwords, account tokens, manufacturer connection parameters, pairing codes, credentials or private keys;
- complete vehicle logs; or
- unredacted recordings or screenshots.

Use the private reporting process above when evidence cannot safely be summarised publicly.

## Immediate safety response

If AVM Recorder appears to affect a factory camera, reverse-view function, infotainment stability or another vehicle system:

1. stop recording;
2. close or uninstall the app when it is safe to do so;
3. confirm that the factory function has recovered; and
4. report the observation without interacting with the app while driving.

Do not rely on AVM Recorder as a safety system or as the only record of a trip or incident.

## Response expectations

This is a small independent project and does not currently provide a guaranteed response or remediation time. Safety-relevant and reproducible reports will be prioritised.
