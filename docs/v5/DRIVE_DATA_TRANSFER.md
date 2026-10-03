# Recorded drive data transfer — private preview

Target build: 5.1.0-drive3. This extends the existing paired video transfer. It adds no cloud dependency, map API key or location upload service.

## User flow

1. On the car, enable **Location & speed** and/or **Vehicle data with recordings** before recording. Both are optional and independent.
2. Make a normal recording and stop it. Open playback, select the desired segments and select **Include route & vehicle data** before sending.
3. Receive on the updated paired phone. Open the video and its drive-data panel. Scrubbing and map taps follow recorded samples across the current playlist; missing values remain unknown. The phone uses the recording's coordinates, not its current position.
4. Choose **Street map** to load a road basemap on either device, or keep the offline local trace. Drag to explore, then use **Recenter** to follow playback again. **Fit route** shows the playlist route; tapping a measured point seeks the video. Both ends label alignment as approximate. This is recorded-route review, not navigation.

The checkbox resets for a new playback session. List-menu quick-send remains video-only. Time-lapse, factory Sentry recordings and videos without a valid normal-recording clock are not eligible. A recording with no retained samples may transfer an empty attachment and display no recorded data.

## Wire contract

- `/health`: optional `driveDataFormats` includes `drive-data-v1` only on the new receiver.
- `POST /api/uploads`: optional `driveData` descriptor (schema, byte size, SHA-256, recording UUID, half-open elapsed-time window and alignment).
- `PUT /api/uploads/{id}/drive-data`: one bounded JSON body, at most 4 MiB. Uses the same paired identity, pinned TLS and commit-time authorization as video chunks.
- Upload status includes `driveDataReceived`; completion with declared data requires a valid attachment before the MP4 publication point.
- Repeated create requests must match the original sidecar and descriptor. Retry reuses the same locally snapshotted bytes.

The attachment contains only GPS and vehicle observations within `[segmentStartedElapsedMs, segmentEndedElapsedMs)`. Individual vehicle getter times must also fall inside that window. At most 20,000 rows per source are allowed. The parser rejects malformed UTF-8, duplicate or unknown fields, unsupported versions/alignment, non-finite/out-of-range values and incompatible sidecars. A valid schema does not establish sensor accuracy.

## Storage and privacy

The head unit snapshots consented data under app-private non-backup storage, capped at 64 MiB. Completed/cancelled tasks remove snapshots; failed tasks retain them for retry until cleared. Clearing local recording history does not recall an already prepared transfer or a received copy.

The phone stores an integrity envelope in `.drive-data/<video filename>.drive.json`, hidden from the media list and ordinary video sharing. It is bound to the received video identity and exact sidecar. Moving video to Trash includes this file; restoring a renamed video updates the controlled metadata path. Other copies and explicit GPX exports remain independent.

The transport does not log or place coordinates in diagnostics. Data are sent only to the explicitly paired phone. Existing diagnostic reports and explicitly exported GPX files have their own disclosure controls.

## Verification still required on devices

- Record with both data sources enabled across at least two segment boundaries; compare playback on both ends.
- Seek backward and directly into later segments; verify P/turn/brake changes near those times.
- Interrupt/retry and cancel a transfer; verify no incomplete video/data pair is listed.
- Try old video-only receiver behavior, missing GPS permission, an empty data history and Trash/restore with a filename collision.
- Verify English and Chinese layout on the actual head unit and phone. JVM tests and UI compilation do not replace device rendering or a vehicle run.

Both ends include MapLibre Native with OpenFreeMap street tiles. Online map requests reveal the viewed area to the provider; the actual route file stays local. Internet failure falls back to a local trace. Three-camera recording and time-lapse data synchronization are not included.
