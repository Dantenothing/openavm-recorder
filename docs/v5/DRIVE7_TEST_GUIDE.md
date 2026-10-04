# OpenAVM Recorder 5.1.0-drive7

Private head-unit test build, version code 112. Install over the existing Recorder without uninstalling. The phone stays on 5.1.0-drive4; pairing, video transfer and playback formats have not changed.

## What this build addresses

The supplied drive6 logs contain four cabin-recording failures roughly 60 seconds after the first segment starts, all with `ENCODER_FORMAT_CHANGED_MID_RUN`. The application treated a second codec output-format notification as a fatal error without comparing its contents.

Both continuous recording backends now compare the track configuration. Compatible repeated notifications keep the existing track and continue recording, including across a file boundary. A genuinely incompatible or unreadable configuration still stops with a specific error; an existing MP4 track is never silently reconfigured. The supplied old logs do not contain the actual two formats, so hardware confirmation is still required.

This build also detects sustained loss of surround input after 15 seconds and enters the existing stop/cleanup flow instead of appearing to record for many minutes without frames. Actual cleanup can take longer. The home page offers **Retry preview** after a failed floating preview once all camera, GL and recording owners are confirmed released. It does not bypass unresolved ownership or automatically retry.

Short diagnostics preserve per-channel first failures separately from later cleanup errors and recover specific errors from older retained logs. Detailed multi-part exports should no longer be needed for this known failure. No video, raw codec configuration bytes or account credentials are added to the short report.

## One stationary check

1. Install and review the two setup pages. Keep **Cabin on / IR off** for the same setup that reproduced the failure. Automatic recording on return requires confirmation after an update.
2. Start normal surround recording with cabin enabled. Leave it running for at least **three minutes**, crossing the one- and two-minute file boundaries. Check that the recording indicator and preview continue.
3. Stop once, allow saving/cleanup to finish, and check the home preview. If an error leaves a failed preview, use **Retry preview** when it becomes available. Start again, let it record briefly, then stop.
4. In the library, verify that both surround and cabin footage continue beyond one minute and that seeking across segments works. Existing HUD/map options can remain enabled; a road trip is unnecessary for this recording check.

If recording stops or the preview stays black, copy **Settings → Developer tools → Recording diagnostics → Copy short JSON** after the stop settles. One copy is enough to start investigating. If developer tools are hidden, tap the version entry seven times. There is no need to clear logs, reinstall from scratch or repeat a long drive.

## Verification boundary

Local unit tests, Android test compilation, lint, APK package/version, signing and archive checks are recorded in `validation.json`. No head unit was connected while building this candidate, so successful real-camera recording, visual rendering and camera recovery are not claimed as locally verified. The installed phone package and public GitHub releases are unchanged.
