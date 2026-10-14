# OpenAVM changelog

## 5.1.0 — 2026-10-14

- Recorder 5.1.0 / 134 and Phone 5.1.0 / 56: optional multi-camera recording, recorded GPS/vehicle data, HUD and route playback.
- Session playback with persistent players across compatible segments, improved entry speed, selection and export.
- Retained camera handoffs, bounded post-stop preview recovery and safe cabin format rollover; private remote support is disabled in public builds.
- Both apps retain local secure pairing. Phone still requires user-imported configuration for optional cloud controls.
- [Release notes and known limits](docs/v5/RELEASE_NOTES.md).

## 5.0.1 — 2026-09-26

- Phone-only update: removes bundled protocol parameters and introduces optional local configuration import with safe upgrade migration. Recorder remains 5.0.0 / 102.
- The original bundled Phone 5.0.0 APK was withdrawn. [Archived notes](docs/v5/RELEASE_NOTES_5_0_1.md).

## 5.0.0 — 2026-09-26

- Vehicle Recorder: continuous surround segmentation, floating mirror and return recovery, date/grouped recording management, emergency clips, and secure phone transfer.
- Unified Android Phone: vehicle status, preconditioning, factory Sentry rules, four widget sizes, separate cabin-temperature acquisition, and integrated recording playback/export.
- Phone release identity remains `com.dante.zeekrbridge` for in-place upgrades. Version 5.0.0 / 51 is a non-debuggable release; Recorder is 5.0.0 / 102.
- Added bilingual setup and upgrade guides. V4 connections require secure re-pairing after both ends are updated.
- [Full V5 notes](docs/v5/RELEASE_NOTES.md).

## V4 / 4.0.0

Vehicle: **4.0.0 (57)**. Optional Android Companion: **4.0.0 (29)**.

- Publishes the current USB recording/export, media library, phone transfer/editing and dual-device sound-tool implementation.
- Adds six-language UI coverage, correct square-video playback, an integrated phone video detail/player, matching icons and simpler vehicle settings.
- Retains the owner-retested fix for an extra internal clip after vehicle-away USB loss, with shared camera-close protections.
- Excludes custom parking Sentry capture and AI assets from release APKs; factory Sentry media remains supported. Return-to-car automatic recording is not included.
- Provides current English/Chinese installation and usage guides, public build verification, and a concise paused-research reference.

See [full V4 release notes](GITHUB_RELEASE_V4.md), [testing limits](USER_GUIDE.md#known-limits) and [build evidence](DEVELOPMENT.md). The owner's RC2 recorder and phone UI acceptance precedes the final cosmetic changes; local checks do not establish all-vehicle compatibility.

## Previous public releases

- [v0.3.0-alpha8-safe-lifecycle](https://github.com/Dantenothing/openavm-recorder/releases/tag/v0.3.0-alpha8-safe-lifecycle)
- [v0.2.0-alpha9](https://github.com/Dantenothing/openavm-recorder/releases/tag/v0.2.0-alpha9)
- [v0.1.1-rc4](https://github.com/Dantenothing/openavm-recorder/releases/tag/v0.1.1-rc4)
- [v0.1.0-alpha](https://github.com/Dantenothing/openavm-recorder/releases/tag/v0.1.0-alpha)

Private experiment and field-test handoffs remain local; they are not public release acceptance records.
