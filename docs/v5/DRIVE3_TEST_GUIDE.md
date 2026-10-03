# OpenAVM recorded-drive test — 5.1.0-drive3

Install both APKs over the corresponding existing apps. Do not uninstall first: the package IDs and signing identity are retained so recordings, settings and pairing can survive the update. This is a private test build, not a change to the public GitHub release.

This build connects the complete ordinary-recording flow: optional GPS and vehicle observations, local playback, selected-video data transfer, and synchronized recorded-route playback with street maps on both devices. No Zeekr cloud login or private server is needed for that flow.

## One combined test

1. In the recorder settings, enable **Location & speed** and **Vehicle data with recordings**, then allow location access. Both options are independent and initially off. Stop the manual signal checker before recording.
2. Record a normal video across at least two segment boundaries. A parked test can verify maps, controls and transfer; a drive is needed later to assess a moving route. Check the recording status, stop, and wait for saving to finish.
3. Open the recording and **Route & drive data**. Watch video beside the route, then seek directly to another segment and back. Vehicle and GPS speeds are labelled separately; missing data stays unavailable.
4. Choose **Street map** to load roads, then drag the map, use **Recenter** and **Fit route**, and tap a recorded point to seek video. A parked recording may have only a small cluster of GPS points. Online map requests reveal the viewed area to OpenFreeMap; the route file is not uploaded.
5. In the player's send panel, select the videos and **Include route & vehicle data**. Start receiving on the paired phone and send. The recording-list quick-send shortcut remains video-only. Keep both devices connected until complete.
6. Open the received recording on the phone, expand **Drive data**, and repeat the same playback and map actions. The phone uses the car's recorded points, not the phone's current location. It can review its received copy with the car disconnected.
7. Turn off internet during playback. The local trace and video must remain usable; a failed street map offers retry. This build does not download an offline city map. Try folding/unfolding the map and leaving/returning to the app as well.

Optional regression checks: cancel and retry a transfer; move a received recording to Trash and restore it; play an older video without data. A normal video share does not silently attach route files. **Save route GPX** explicitly exports coordinates for the current playback playlist.

## Feedback

After stopping recording, copy the car's existing combined vehicle field-check JSON. It now adds map mode, loading outcome and recording-data counts. On the phone, **Copy playback report** in the expanded drive panel provides a small JSON with playback/map outcomes. The new summaries do not include coordinates; an older field report may contain coordinates if they were deliberately included in that test.

Tell us which step failed and whether it happened on the car or phone. Do not publish private routes or credentials in a public issue.

## Limits to keep clear

- Video/data alignment is approximate (`SEGMENT_ELAPSED_APPROX_V1`), not a calibrated frame-accurate measurement.
- Missing GPS is not reconstructed or snapped to roads. Old videos without recorded GPS cannot gain a historical route later.
- Time-lapse data synchronization and simultaneous three-camera recording are separate work and are not included in this build.
- Automated tests, compilation and package checks are recorded in `validation.json`. They do not establish native map rendering, actual sensor quality or paired-device transfer acceptance on your hardware.
