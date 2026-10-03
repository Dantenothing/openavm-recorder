# Drive data and map playback — development preview

Updated 2026-10-03. Target: **both OpenAVM Recorder and OpenAVM Phone**. This is development documentation, not a public V5 release announcement.

## Current implementation

`5.1.0-drive3` brings recorded road-map playback to both apps. Both use MapLibre Native OpenGL ES 11.13.5 with OpenFreeMap Liberty street tiles. No Google Play services, API key or private server is required. The default view is a local recorded trace. Choosing **Street map** loads online tiles after an inline disclosure; tile requests reveal the viewed area to the provider. The route file is not uploaded. Map load errors and timeouts leave local trace/video playback available. This does not promise a downloaded offline road map.

Playback shows the selected playlist with approximate sample alignment, map follow/recenter, fit, zoom and point-to-video seeking. A drag stops following until recentered. Only existing measured points are seek targets, and gaps remain disconnected. The head unit keeps the video visible beside its map; the phone keeps the drive panel alongside the playback controls.

The paired transfer introduced in `drive2` is retained. In the head-unit playback panel, **Include route & vehicle data** is off by default. It sends a fixed GPS/vehicle-data attachment for each selected normal-recording segment through the existing paired TLS connection. The attachment is clipped to that segment's monotonic time window; selecting a segment does not send the full session history. Quick-send from the recording list remains video-only. A receiver without the new capability is rejected explicitly when data is selected; the sender does not silently discard it.

The phone stores this attachment separately from the video and shows recorded route/vehicle data in playback, using verified attachments from the current playable playlist and its resolved media durations. Unresolved durations stop global route projection rather than shifting later points onto guessed times. Video and metadata retain separate integrity checks. The receiver verifies the session UUID, time window, sidecar and content hash before publishing a declared video/data pair. Attachments follow Trash and restore, including renamed videos. Ordinary video sharing does not automatically include coordinates. The phone uses the car's recorded samples and does not need its own location permission for this view.

The contract is `drive-data-v1`, with alignment `SEGMENT_ELAPSED_APPROX_V1`, a 4 MiB limit and at most 20,000 samples per source per segment. There is no interpolation of controls and no continuation across known gaps. The alignment is deliberately approximate: the current recorder observes the first frame after camera/queue latency, not at a calibrated sensor clock boundary. Phone clock, import time and file modification time are not alignment sources. Empty data remains an explicit no-recorded-data state; old videos cannot acquire a historical route later.

`5.1.0-drive1` adds an optional, default-off **Vehicle data with recordings** setting to the head unit. It records the SDK's read-only speed, gear, indicator request, left/right indicator lamps, brake pedal and brake lamp observations. It is independent of the existing optional GPS recorder. No cloud account, protocol configuration or private server is needed for local recording and playback.

Data is stored separately from the original video. The playback overlay identifies **Vehicle** or **GPS** speed rather than silently substituting one for the other. The drive playback panel shows both sources when available. Missing, rejected or stale observations remain unknown. Gear and ignition state are not treated as proof of movement.

Vehicle SDK reads run on a dedicated process-wide worker with non-blocking admission shared with the manual field checker. A separate watchdog expires a blocked read after two seconds and stops that recording's vehicle journal; it does not create a replacement reader while the original call is still blocked. Manual field checks temporarily take priority. Video callbacks do not wait for SDK or journal IO.

Each vehicle value carries its own getter-return monotonic timestamp. This is an **observation time, not an ECU measurement timestamp**. Values are polled up to four times per second. Playback uses preceding observations for at most two seconds, without interpolating controls or bridging known corrupt gaps. This is approximate video alignment, not frame-accurate capture.

The new journal is under app-private, non-backup storage, keyed by the recorder session UUID. Bounds are 8 MiB per session, 64 MiB total, 30-day retention and a secondary row-count cap. Storage limits stop metadata collection, not video. Deleting vehicle history preserves video and GPS history. GPS history has separate existing limits and controls.

The head-unit drive panel can explicitly export recorded GPS points from the displayed playback playlist as **GPX** through Android's file picker; neighboring session history is excluded. A tested GeoJSON serializer is also present for optional future export workflows; it does not have a product UI. Invalid points, provider changes and gaps split the track. There is no route reconstruction or road snapping.

The optional **Google Maps · this point** shortcut opens a single recorded coordinate externally, independently of the embedded street map. The coordinate is sent to Google after a tap; GPX exports also contain real coordinates.

The field report includes coordinate-free map runtime outcomes (mode, load result and bounded error category), plus read-only presence checks for Google Play services, Play Store and Google Maps. Package presence does not prove Maps SDK compatibility. This check makes no network request and includes no coordinates. A second addition summarizes counts, durations, data gaps and available fields for at most three recent recording sessions, without coordinates or raw readings. It skips loading journals during active recording. Older manually collected field reports can still include coordinates if the owner chose to include them in those tests.

## What is not implemented

- Three-camera recording. The preview probe remains separate from production recording.
- Time-lapse route/vehicle synchronization. `TelemetryVideoWindow` rejects unsupported time compression rather than inventing an offset.
- Speed/GPS watermarks burned into original files, road snapping, inferred routes or background uploads.
- Recovery of routes for old videos that were recorded without location data.

## Evidence and remaining verification

The owner's `5.1.0-field1` two-minute check returned 61 candidate signals, with 55 accepted by their decoders and 22 changing. It captured vehicle speed from 0 to about 3 km/h, P/D/R changes, left/right indicator requests and brake changes. Decoder acceptance alone does not physically validate every signal.

It also returned 120 fresh GPS fixes (plus one rejected cached fix), with reported horizontal accuracy around 2.2 m. That is receiver-reported accuracy, not proof of an exact one-to-two-metre displacement. The recorder was idle during this check: GPS plus vehicle data **during actual recording remains to be verified**.

A separate preview probe observed camera IDs 2, 1 and 0 together for approximately 20 seconds, at roughly 29 displayed frames per second. Cleanup was confirmed. This does not prove simultaneous encoders, playable files, long-duration stability or wake recovery.

The new build corrects enum sentinels and a specifically identified shared seat-occupancy code family. Occupancy's physical meaning remains provisional; it is not among the recording allowlist.

Automated validation and APK identity are recorded separately in the delivered `validation.json`. UI test compilation is not UI execution or vehicle acceptance.

## Dashcam research and design choice

Thinkware documents that disabling the visible speed stamp still leaves recorded speed available in its PC viewer. This supports keeping original video and telemetry distinct. BlackVue documents route/speed review as part of its GPS workflow. These are product examples, not evidence that OpenAVM has passed equivalent testing. [Thinkware](https://support.thinkware.com/hc/en-us/articles/26023860586259-Speed-Unit), [BlackVue](https://blackvue.com/products/external-gps-receiver).

The reviewed Zeekr Shortcut v2.0.0 source uses a recorder/encoder/file per camera, shared approximate timestamps for grouping, and playback drift correction. Its three streams are not one physically synchronized six-view video. OpenAVM already has continuous encoding across keyframe-based file rotation; replacing that with stop/restart segmentation merely to imitate another app would risk regressions. The useful next experiment is independent cabin encoders with a common session clock, bounded buffers and file verification. [Reviewed source](https://github.com/dts88/zeekr-shortcut-car/tree/6379201003a786f1601e5a99990703d3844b5598).

## Recommended dual-end architecture

1. Record original video, GPS and vehicle observations locally, with one session UUID and a monotonic clock. Keep source, accuracy, freshness and gaps.
2. Improve the current approximate segment clock to a measured relationship between each segment's first encoded frame and the session clock. Resolve normal recording first; compressed time requires an explicit mapping.
3. Verify the new versioned **Include route and vehicle data** opt-in on actual devices, including interrupted transfer and cancellation. It preserves existing TLS, pairing and access controls. Old clients retain video-only transfer.
4. Verify the new phone playback contract against the same recording on the car. Do not load the old standalone `phone` module by mistake: the unified app's companion sources come from `ZeekrBridgeCompanion`.
5. Verify the shared street-map renderer on both real devices, including GPU/video coexistence and network loss. Playback time drives the marker; recorded-point taps seek video, and dragging suspends following until recentered.
6. Consider optional privacy trimming and annotated exports after the original recording/transfer path is verified.

The car does not need internet while collecting GPS and vehicle observations. Online street tiles may need connectivity during review. A tile provider's offline license and cache rules are separate from the right to retain one's own GPS points.

Existing `SegmentSidecar` includes recording session/process IDs, monotonic and UTC start/stop times, recording mode and time-lapse multiplier; schema 9 also has continuous-encoding PTS fields. A process ID is not a boot ID. The upload remains per MP4 with at most 256 KiB of sidecar JSON; the new route attachment uses a separate bounded endpoint. Descriptor idempotency, stable snapshot hashes, authenticated upload, atomic commit and Trash/restore participate in the same lifecycle. The phone loads timing from the verified attachment bound to the video sidecar.

## Map provider decision

Both apps now use the same native MapLibre OpenGL ES renderer with OpenFreeMap Liberty. OpenFreeMap documents mobile use through MapLibre Native and does not require a project key or registration. This avoids an unverified head-unit GMS dependency and avoids asking each user to set up a Google project. [OpenFreeMap quick start](https://openfreemap.org/quick_start/), [MapLibre Android](https://maplibre.org/maplibre-native/android/examples/getting-started/).

The map retains linked OpenFreeMap, OpenMapTiles and OpenStreetMap attribution. Online tiles are optional and are loaded only for a visible, resumed road-map panel. Local trace, route selection and video remain available without internet. There is no bulk tile download, offline-region pack or service-availability guarantee. Normal SDK cache contents are not a guaranteed offline map. [OpenFreeMap](https://openfreemap.org/), [provider privacy](https://openfreemap.org/privacy/).

The app does not upload its route file to a map service, snap measurements to roads, calculate directions, request the phone's location or change vehicle controls. Tile, style, font and sprite requests still contact the map provider/CDN and expose the viewed area and network information; the inline map choice explains that boundary. Renderer and map-data licences are separate and are recorded in the third-party notices.

## Acceptance before a dual-end release

- Offline recording with GPS and vehicle data enabled: playable files across at least two segment boundaries, telemetry clocks continuous and no capture performance regression.
- A deliberately unavailable or blocked vehicle reader cannot block stop, close or video rotation; unknown values remain unknown.
- Pause/resume, screen-off and subsequent recording do not reuse stale observations or a different session's history.
- Reopening and seeking around a segment boundary aligns the same observation on both devices; time-lapse stays explicitly unsupported until mapped.
- Transfer is optional, versioned, bounded and authenticated; corrupt, oversized, mismatched-session or unsupported data cannot corrupt the media library.
- No location permission, no GMS, no network and missing tiles have understandable fallbacks. A map error cannot prevent video playback.
- Check English and Chinese controls on actual device sizes, including long recordings and dense routes.

## Local trial without a drive

Park safely, stop the manual field checker, enable **Location & speed** and **Vehicle data with recordings**, then make a normal short recording. Toggle a turn indicator and press/release the brake if appropriate while remaining parked; movement is not required. Stop recording and open the new video, then **Drive data**. The vehicle line should show P and available speed/indicator/brake observations; GPS speed can legitimately be missing or differ slightly while parked.

Seek backwards and forwards. Missing samples must not become fabricated zeros. If no route appears, check that location recording was enabled before the recording and precise location was allowed. An old video cannot acquire a route retrospectively.

Transfer with **Include route & vehicle data**, then compare the same playlist on the phone. Turn on **Street map** on both ends, try map dragging/recentering and point-to-video seeking, then disable the network and confirm local trace/video remain usable.

Optionally save the GPX to your chosen location; do not publish it casually because it contains coordinates. After stopping, use the existing merged field report for follow-up diagnostics. No public GitHub release is changed by this development delivery.
