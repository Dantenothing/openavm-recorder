# OpenAVM Recorder 5.1.0-drive8

Private head-unit candidate, version code 113. Install over drive7 without uninstalling. The phone remains on 5.1.0-drive4; pairing, transfer and playback formats are unchanged.

## What changed

Drive7's field report shows cabin recording rejecting a complete but different codec format about 60 seconds after starting, around the expected first file cut. The old report does not record whether a cut was pending or which field changed. Cabin capture stops; surround loses frames around the cabin close and later stops through its 15-second watchdog. This timing does not prove a camera-driver cause.

Drive8 lets a normal direct-codec cut assign a complete changed configuration to the next independent MP4, while retaining the camera and encoder session. The old file keeps its original track. The change is allowed only with an existing cut request, unchanged image layout and a keyframe starting the next file. Other transitions remain explicit errors instead of producing a file with the wrong track configuration.

Both short-report copy buttons now preserve format details by camera channel, including the exact field category that changed or failed validation. Raw codec data is not included. Later stop, restart and surround-watchdog events cannot silently replace the cabin evidence in the retained summary.

The separate zero-frame startup reported in drive7 is still under investigation. Its format error appears during stopping, so it is not presented as proof of the startup cause. This candidate records more precise missing/invalid-field evidence if that happens again.

## Stationary check

1. Keep **Cabin on / IR off** to match the failing setup. Review the update choices; automatic recording on return still requires your confirmation.
2. Record for at least **three minutes**, crossing the first and second file boundaries.
3. Stop and wait for saving/cleanup, then start again briefly and stop.
4. Check that both surround and cabin recordings continue beyond one minute and can seek across files.
5. Copy either the existing **away/sleep summary JSON** from the floating menu or **Settings → Developer tools → Recording diagnostics → Copy short JSON**. One report includes retained channel-format evidence; there is no need to copy all detailed report parts. Developer tools can be shown by tapping the version entry seven times.

If startup has no picture or recording stops, copy the same short report. Do not clear diagnostic history or uninstall to test this candidate. A road trip is unnecessary for this check.

## Verification boundary

Local tests, lint, package identity, signing and archive checks are recorded in `validation.json`. Android tests are compiled, not executed on a head unit. Hardware continuity, visual rendering and startup recovery remain unverified for this binary. Public GitHub releases are unchanged.
