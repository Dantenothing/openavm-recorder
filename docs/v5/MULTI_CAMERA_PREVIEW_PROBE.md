# Multi-camera preview probe

This is a research build, not a multi-camera recording release. It tests whether the selected Camera2 streams can keep delivering new preview buffers together for 20 seconds. The vehicle has not yet been tested with this build.

## Why this experiment exists

The public [Zeekr Shortcut implementation](https://github.com/dts88/zeekr-shortcut-car/blob/483304ade9496b6d59ebe92af79e3a93dda60037/app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java) opens separate Camera2 devices for surround and two cabin streams. The surround stream contains four views in one composite raster; this is three Android camera devices, not six.

OpenAVM's older concurrent recording experiment rejects combinations absent from `concurrentCameraIds`, and also requires a positive exact-session query. Android documents that `isConcurrentSessionConfigurationSupported` returns false for an unlisted combination. Consequently, those checks do not establish what an explicit runtime attempt on a particular vehicle would do. See the [CameraManager reference](https://developer.android.com/reference/android/hardware/camera2/CameraManager).

Only this isolated experiment can try an unlisted combination, after a fresh, one-time user confirmation. Production recording admission is unchanged. A failed declaration query is not treated as an empty declaration.

## Build and entry point

```text
gradlew.bat :app:assembleRelease -PcameraConcurrencyProbe=true
```

`cameraConcurrencyProbe` defaults to false. The entry point is **Settings → Multi-camera preview test → Open preview test** in a probe-enabled build. The Activity is not exported. Opening it does not open cameras; **Read camera info** only queries metadata. Permission approval does not automatically start a test.

The preview build keeps the existing package name and upgrade identity. It also retains the optional, default-off local GPS/speed experiment from the telemetry preview. Neither experiment is enabled automatically after installing.

## Vehicle test order

1. Park, stop recording, and close other camera previews. Open the probe and select **Read camera info**.
2. Select **Surround + Cabin A**. Check the parked option. If the combination is not listed by the system, separately choose the one-time runtime attempt. Start the test.
3. Confirm that both pictures are current. The per-stream counters must advance independently. Wait for the result and confirmed camera release; save or copy the report.
4. Verify that normal single-camera recording and the original vehicle camera display still work before continuing. Stop if either fails.
5. Repeat for **Surround + Cabin B**, then **All three cameras**. Each selection or start clears the previous confirmations.

Cabin A/B use the existing camera mapping, without renaming or swapping the user's saved source roles. Their physical locations must be identified from the actual pictures. The probe requests the recognized surround composite size and a lower declared cabin preview size near 640×480; it does not force an undeclared size.

## Result interpretation

| Result | Meaning |
| --- | --- |
| `PREVIEW_OBSERVED` + cleanup `CONFIRMED` | All selected textures advanced throughout the 20-second observation, and all owned camera/output resources were released. |
| `FIRST_FRAMES_TIMEOUT` | Not all streams became fresh within the 15-second startup deadline, including any wait for previous cleanup. |
| `STREAM_STALLED` | At least one stream stopped advancing for more than 1.5 seconds. |
| `OBSERVATION_INTERRUPTED` | The observation/control loop stalled; elapsed time alone is insufficient to certify continuous preview. |
| Open/session/capture error | The attempted configuration failed. The report includes the affected camera and error category. |
| cleanup `UNCONFIRMED` | A native owner has not confirmed release. No new OpenAVM camera operation is admitted while that owner remains unresolved. A late close can still complete cleanup. |

Frame evidence uses strictly increasing `SurfaceTexture` timestamps. Capture callbacks and repeated drawing alone do not count. These are displayed-buffer counts, not an independent measurement of the sensor frame rate. A preview pass does **not** prove simultaneous recording, encoder capacity, USB throughput, long-term operation, segmentation, or sleep recovery.

## Lifecycle and report scope

All selected devices are opened before sessions are configured; all sessions are configured before capture requests begin. For declared combinations the exact-session check remains in place. Only an explicitly confirmed unlisted combination skips that declaration-based check.

Leaving the page, screen-off, permission loss, severe thermal status, a capture failure, or a stalled stream ends the test. There is no auto-retry, background camera service, recording, photo capture, or test-specific wake lock. Native open/close uncertainty retains camera ownership instead of releasing it on a timer. A detached `SurfaceTexture` is retained until camera cleanup completes.

The latest report is saved atomically in app-private, non-backed-up storage as `multi-preview-last.json`; it can also be copied or saved explicitly. It includes app/source identifiers, device model/firmware, camera IDs, **requested** output sizes, frame counts/timing, bounded lifecycle events, and cleanup results. It contains no image pixels, GPS data, account credentials, or video. A requested size is not a claim that the actual image dimensions were independently measured. Early admission rejections are shown on screen and do not create a capture-run report.

## Next step after vehicle evidence

If both pairs and the triple pass, add a separate short three-file recording experiment and inspect each file's decodability, frame count, timestamps, duration, and cleanup. Only then evaluate long recording, segmentation, sleep/return, grouped playback, and phone transfer. Do not enable three-camera production recording from a preview-only result.

## Development verification — 2026-10-03

- 1,157 app JVM tests passed, including 16 new probe policy, frame-evidence, and texture-ownership tests.
- Debug build and Android test APK compilation passed. Debug lint completed with 0 errors, 200 warnings, and 19 hints across the existing application; no probe-file warning remained.
- A separate generation without the opt-in property confirmed `CAMERA_CONCURRENCY_PROBE_ENABLED = false`.
- Seven Android tests cover synthetic English/Chinese UI, admission controls, metadata-only inspection, and pending export restoration after Activity recreation. They compiled, but execution and visual inspection remain incomplete: the local emulator failed to boot reliably, including on a fresh AVD. No Android test pass is claimed.
- Static lifecycle review found the pending-export recreation issue; the pending report now survives saved-instance-state restoration. No vehicle run, real camera overlap, or real recording test has been performed.

The delivery folder contains the APK checksum, signature verification, build log, and a separate `validation.json`. This remains a private research build; it has not been uploaded to GitHub.
