# AVM Recorder

Experimental surround-view recorder for compatible Zeekr App Lab environments.

> [!WARNING]
> AVM Recorder is experimental, unofficial software. It is not affiliated with or endorsed by Zeekr. It is not a replacement for an OEM dashcam or any vehicle safety system. Do not interact with the app while driving.

## What it is

AVM Recorder records and displays the surround-view video stream exposed to an ordinary third-party Android app in a compatible Zeekr App Lab environment.

The current alpha provides:

- a 2×2 live view labelled Front, Rear, Left and Right;
- segmented recording to the app's internal storage;
- protected/saved events that automatic cleanup does not remove;
- a thumbnail-based recording library;
- four-view playback, seeking, previous/next recording navigation and single-view zoom;
- optional display-only fisheye correction; and
- automatic cleanup controlled by a storage limit and reserved free-space threshold.

Phone transfer is not part of this alpha. The phone page remains disabled while a reliable transport for this vehicle environment is investigated.

## How it works

On the tested vehicle, App Lab exposes one already-processed `1280×5140` composite surround-view stream. The stream contains four approximately `1280×1280` views arranged vertically, with separator rows between them.

AVM Recorder:

1. requests that single composite stream through standard Android Camera2 APIs;
2. maps its four regions into a 2×2 display without changing the camera producer;
3. records the original composite stream through Android's media APIs; and
4. applies view layout, zoom and optional lens correction only at the display layer.

The app does **not**:

- require root;
- unlock the bootloader;
- bypass Android permissions;
- escalate privileges;
- modify system files;
- use a proprietary Zeekr SDK or extracted Zeekr binaries; or
- directly access four individual ADAS camera feeds.

## Current test status

The first public test version is `v0.1.0-alpha`.

It has been tested on an Australian Zeekr 7X. Testing has included approximately two hours of continuous recording and normal head-unit use. During that testing, no obvious recording dropout, head-unit fault or abnormal behaviour of the factory 360° camera was observed.

Those observations do **not** prove that the app has no shared-resource impact or other risk. Test coverage is limited to one vehicle environment and has not established long-term reliability, safety or compatibility with other regions, models or software versions.

## Storage limitation

The tested App Lab environment does not allow this app to write recordings directly to external USB storage. Recordings are currently written to the app's internal storage.

Observed recording data rate:

- approximately `200 MB/min`;
- approximately `3–4 MB/s` sustained writes; and
- a recorded composite profile around `1280×5140` at `28 Mbps`.

Protected events are excluded from normal automatic cleanup, so they can continue to consume storage until manually deleted.

## Areas still under investigation

- CPU and GPU load;
- memory usage and memory bandwidth;
- hardware encoder usage;
- storage I/O and long-term flash wear;
- thermal impact;
- dropped frames and gaps between segments;
- long-duration background behaviour;
- recovery after the camera is reclaimed by the vehicle; and
- interaction with factory infotainment and camera resources.

If the vehicle reports that the camera is already in use, stop recording and retry after the factory camera function has released it. Stop using the app if any factory camera, reverse-view or infotainment behaviour appears abnormal.

## Security and inspection

The source code is public so that users can inspect it before installation. Users are encouraged to review:

- [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml);
- requested permissions and exported components;
- Gradle dependencies;
- network behaviour;
- Zeekr-specific assumptions; and
- the recording and storage implementation.

The alpha requests camera, notification, foreground-service and wake-lock permissions. It does not request the Android `INTERNET` permission.

See [SECURITY.md](SECURITY.md) for reporting security, privacy, excessive resource use or unexpected vehicle-system interaction. Third-party component licensing is documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Installation

The confirmed installation path for the tested App Lab environment is:

1. Open this repository's GitHub Releases page in the head-unit browser.
2. Download the signed `AVM-Recorder-v0.1.0-alpha.apk` release asset.
3. Open the downloaded APK and confirm installation through the system package installer.
4. Launch AVM Recorder and grant the camera permission when requested.

Do not install a file whose name ends in `-unsigned.apk`; unsigned files are build-validation artifacts and are not installable release packages.

## Building from source

Requirements:

- Android SDK with API 36;
- JDK 17; and
- the included Gradle wrapper.

On Windows:

```powershell
.\gradlew.bat clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

On macOS or Linux:

```bash
./gradlew clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Without a locally configured private signing key, Gradle produces `app-release-unsigned.apk`. Signing keys and credentials must never be committed to the repository.

## Known limitations

- Compatibility is currently confirmed only on the tested Australian Zeekr 7X App Lab environment.
- Recordings use internal storage rather than external USB storage.
- Browser and dedicated-phone-app transfer are disabled in this alpha.
- The special `1280×5140` composite video may not play correctly in every third-party media player.
- Camera access can be unavailable while the vehicle's factory 360° or reverse-camera function owns the camera.
- The app has not completed long-term thermal, storage-wear or multi-hour/multi-day reliability testing.
- The app cannot establish that vehicle compute, memory or storage bandwidth is unaffected.

## Disclaimer

AVM Recorder is experimental, unofficial software provided for testing. It is not affiliated with, approved by or endorsed by Zeekr. Use it at your own risk. Users are responsible for evaluating whether it is suitable and lawful to install and use on their own vehicle, and for stopping use if any unexpected vehicle behaviour occurs.

## Source availability and licensing

The source code is published for inspection. No project-wide licence is granted at this time, and no `LICENSE` file is included. Third-party components remain governed by their own licences as described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
