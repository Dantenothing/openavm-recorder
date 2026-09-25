# Phone 5.0.1 candidate verification

Date: 2026-09-26. Local candidate only; no GitHub release or asset was changed by this work.

## Artifact

| Item | Verified value |
| --- | --- |
| Package | `com.dante.zeekrbridge` |
| Version | `5.0.1` / version code `52` |
| Intended filename | `OpenAVM-Phone-5.0.1.apk` |
| Size | 15,430,252 bytes |
| SHA-256 | `1c80ed8a144e78270a924beeb8558495b1b90dcc25ec9de71657db170ef3f492` |
| Signing certificate SHA-256 | `529951606101aba4d45f94f7c68260c8268073bf3769b9f939161f0533d5994e` |
| Installer behaviour | Same signing identity/package as the retained Phone 5.0.0; in-place installation succeeded |
| Runtime package check | One launcher; installed release has no DEBUGGABLE flag |

The certificate retains the historical Android Debug subject name for upgrade compatibility. APK debuggability was checked separately; this is not a change of signing identity.

## Parameter distribution

- The build no longer reads a private protocol file or adds the former generated configuration assets. There is no built-in configuration fallback or automatic configuration download.
- The artifact guard scanned all 604 uncompressed APK entries. The old configuration asset was absent, with no non-empty protocol JSON or private-key PEM block/key-store file findings.
- The six actual values from the privately retained Phone 5.0.0 APK were compared in memory with the new APK, including UTF-8, UTF-16LE and JSON-escaped representations. There were no matches. Values were not printed or added to the report.
- The old APK was the negative control: the guard rejected its configuration asset and detected all six reference fields.
- A separate scan of 1,088 current tracked/non-ignored candidate source files found no exact reference-value matches. This was not a scan of every historical Git object.

The empty template is intentionally nonfunctional. Format validation does not establish the source, region compatibility or permission to use a configuration. These checks do not assess Zeekr server authorisation or confer rights to third-party cloud access.

## Automated checks

- Phone release unit tests: **335 passed**, 0 failed or skipped.
- Companion release unit tests: **236 passed**, 0 failed or skipped.
- Release build and Android lint completed successfully. Lint reports **0 errors and 95 warnings**; this is not a warning-free release. Warnings include dependency updates, layouts, existing context retention and the LAN cleartext base configuration. Cloud hosts remain HTTPS-only under both network policy and the request allowlist. The departure receiver warning concerns action validation delegated to its helper; the receiver is not exported.
- **13 distinct Android emulator checks passed**, including the legacy seeding phase. Tests used an offline emulator and generated synthetic account/configuration data, never a real cloud login or vehicle operation.

Android coverage:

1. Missing configuration starts normally and local media initialization is available.
2. Import/remove and invalid-import recovery preserve local files; importing does not create a login.
3. Encrypted configuration storage and two encrypted session storage checks.
4. Optional setup and local Home in English and Simplified Chinese.
5. Seven-step beginner guide and Recorder connection instructions in both languages.
6. Four widget sizes in both languages; vehicle actions and refresh are disabled without setup, while the app entry remains available.
7. Three separate-process lifecycle phases: imported configuration/session persist; old action intents remain invalid after replacement and a new synthetic login; an interrupted configuration transaction clears authorisation on restart.
8. The retained, signed Phone 5.0.0 was installed and seeded using its legacy storage APIs, then upgraded in place. Configuration/session and six old job IDs were cleared before opening an Activity. Home, comfort settings and disabled plans survived. A synthetic media marker, all pairing fields, the encrypted pairing token and the Keystore TLS identity survived. The token still decrypted after migration. An old action intent produced no cloud request.

The upgrade check also passed on the final candidate after another process start. Pairing JSON is compared by content because local media initialization normally rewrites whitespace and field order. This test uses a synthetic media marker, not an end-to-end video transfer or playback session.

## Remaining owner trial

The owner should test their independently prepared configuration and authorised account on the intended phone: import, sign in, select the vehicle, and deliberately re-enable desired rules. Ordinary cloud login/control and real Recorder transfer were not repeated against the actual vehicle in this change.

Do not re-publish the withdrawn Phone 5.0.0. Review this candidate and its user-facing setup instructions before publishing Phone 5.0.1. Keep the vehicle Recorder release and the public project in place.
