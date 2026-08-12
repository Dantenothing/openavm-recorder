# AVM Recorder

[简体中文](README.zh-CN.md)

Experimental surround-view recorder for compatible Zeekr App Lab environments.

> [!WARNING]
> AVM Recorder is experimental, unofficial software. It is not affiliated with, approved by or endorsed by Zeekr.
>
> It is not a replacement for an OEM dashcam or any vehicle safety system. Do not interact with the app while driving.
>
> The fact that the app can access and record a video stream does **not** establish that doing so has zero impact on shared vehicle compute, memory, camera or storage resources.

## What it is

AVM Recorder records and displays the surround-view video stream exposed to a third-party Android application running in a compatible Zeekr App Lab environment.

The current alpha provides:

- a 2×2 live view labelled Front, Rear, Left and Right;
- segmented recording to the app's internal storage;
- protected/saved events that automatic cleanup does not remove;
- a thumbnail-based recording library;
- four-view playback, seeking, previous/next recording navigation and single-view zoom;
- optional display-only fisheye correction; and
- automatic cleanup controlled by a storage limit and reserved free-space threshold.

Phone transfer is not part of this alpha. The phone page remains disabled while a reliable transport method for this vehicle environment is investigated.

## How it works

On the tested vehicle, App Lab allows the application to request one already-processed `1280×5140` composite surround-view stream through Android Camera2.

The stream contains four approximately `1280×1280` views arranged vertically, with separator rows between them.

AVM Recorder:

1. requests that single composite stream through standard Android Camera2 APIs;
2. identifies and maps the four image regions into a 2×2 display;
3. records the composite stream through Android media APIs; and
4. applies view layout, zoom and optional lens correction only at the display layer.

The app does **not**:

- require root;
- unlock the bootloader;
- modify the Android system image;
- bypass Android permission checks;
- escalate application privileges;
- modify vehicle firmware;
- use a proprietary Zeekr SDK or extracted Zeekr binaries; or
- request four separate camera devices or four independent camera streams.

The application operates only with the Android permissions and interfaces available to it in the tested App Lab environment.

## What is known — and what is not

There are important limits to what can currently be concluded from observations made at the Android application layer.

### What has been observed

The application receives a **single processed `1280×5140` composite video stream** rather than opening four individual camera streams.

The four images visible to the application correspond to the vehicle's surround-view / AVM views.

The application performs its own crop, layout and recording operations only after receiving that composite stream.

No root access, privilege escalation, system modification or direct vehicle-firmware modification is required.

### What has not been established

The upstream architecture producing the composite stream has not been independently documented or fully reverse-engineered.

This project has therefore **not established**:

- which ISP, camera controller or vehicle subsystem produces the `1280×5140` stream;
- whether it is generated specifically for the factory AVM / 360° interface or mirrored from another internal video pipeline;
- whether its upstream processing shares memory, ISP, camera, compute or other resources with ADAS or other vehicle functions;
- whether requesting the stream creates additional upstream work or simply attaches another consumer to an already-produced output;
- how App Lab is internally isolated from other infotainment or vehicle services;
- whether App Lab uses an additional container, host process or other isolation mechanism beyond ordinary Android application isolation;
- whether the video encoder is hardware-accelerated on every compatible software version;
- whether sustained recording affects telemetry logging or other storage users under worst-case conditions; or
- whether the same behaviour will remain available after future Zeekr OTA updates.

The existence of a processed surround-view stream alone is **not sufficient evidence to claim architectural independence from ADAS or other upstream vehicle systems**.

The project therefore documents what can actually be observed rather than making assumptions about undocumented vehicle architecture.

## Observed App Lab and infotainment behaviour

Some behaviour of the tested vehicle is relevant when interpreting faults or performance changes during AVM Recorder testing.

### Infotainment interruption observed before third-party apps were installed

During ordinary use of the tested vehicle, one complete infotainment interruption was observed **before AVM Recorder or any other third-party App Lab APK had been installed or run**.

During that event:

- the centre display went completely black;
- the head-up display also became unavailable;
- the infotainment system remained unavailable for approximately two minutes; and
- the system subsequently recovered without user intervention.

The driver did not observe any loss of basic driving functions during the event.

This factual observation is included only as testing context. It shows that, on this particular test vehicle, a similar infotainment interruption had occurred in the complete absence of AVM Recorder and other third-party App Lab applications.

It does not establish the cause of that event, and it does **not** show that AVM Recorder could never cause or contribute to a different infotainment, AVM, ADAS or vehicle-system issue. Any future report should be investigated using logs, reproducible steps and comparison with the app stopped or uninstalled.

### App Lab appears to be subject to resource management

The App Lab interface itself warns that:

- applications that are excessively large may fail to start; and
- applications running through App Lab may be slowed when other head-unit functions are under high load.

This suggests that the platform has some form of resource-management or performance-control policy for App Lab applications and may prioritise factory infotainment workloads when system resources are constrained.

However, the implementation is undocumented and has not been independently verified.

It is currently unknown:

- what CPU, GPU, RAM or storage-I/O limits apply to App Lab applications;
- whether App Lab applications run at a lower scheduler or service priority;
- whether any limits are dynamic or fixed;
- which factory workloads take precedence; or
- whether these controls provide any isolation from safety-related vehicle systems.

The App Lab warning is evidence that the platform anticipates resource contention and has some mechanism for managing third-party workloads.

It is **not proof that an App Lab application cannot affect other head-unit functions**.

## Road testing to date

As of 12 August 2026, AVM Recorder had accumulated approximately **four hours of real-world driving tests** on the development vehicle.

The typical test condition was:

- AVM Recorder running continuously in the background and recording;
- Android Auto operating normally at the same time; and
- normal use of the vehicle and head unit during driving.

During these tests:

- recording continued without an observed failure;
- no obvious sustained video stuttering or recording dropout was observed;
- Android Auto continued to operate normally;
- no abnormal behaviour of the factory 360° camera was observed;
- no obvious abnormal vehicle behaviour was observed; and
- no head-unit fault was observed during this test period.

During observed operation, the head unit's system reporting showed approximately **9 GB of available RAM**.

This was an observed system-reported value rather than an independently validated measurement. It suggests that the tested workload did **not produce obvious RAM pressure on this vehicle during the observed test period**.

It does not establish that memory use is harmless under all conditions, because:

- the measurement is from one vehicle;
- only one known software environment has been tested;
- memory use may change with other applications or workloads;
- longer-duration behaviour has not yet been established; and
- available-memory reporting does not by itself measure memory bandwidth, allocation latency or other shared-resource effects.

The approximately four hours of road testing are therefore useful empirical evidence of current behaviour, but they are **not proof of long-term safety, zero resource impact or universal compatibility**.

## Resource usage and open questions

The largest remaining technical questions concern shared system resources.

Areas still requiring measurement include:

- CPU load;
- GPU load;
- RAM consumption over longer periods;
- memory bandwidth;
- Camera2 buffer behaviour;
- hardware video encoder usage;
- encoder contention;
- sustained storage I/O;
- storage latency while vehicle telemetry or other services are active;
- internal flash write amplification and endurance;
- thermal impact;
- dropped frames;
- gaps between recording segments;
- long-duration foreground/background behaviour;
- behaviour during repeated camera acquisition/release cycles;
- recovery after the camera is reclaimed by a factory vehicle function; and
- interaction with factory infotainment, AVM and other camera-related services.

The approximately `9 GB` of available RAM reported by the head unit during testing reduces concern about **immediate RAM exhaustion under that specific tested workload**, but it does not answer questions about memory bandwidth, scheduler contention or other shared resources.

The project should therefore not currently be interpreted as demonstrating that recording has **zero performance cost**.

Further testing is intended to measure that cost rather than assume either that it is harmless or that it is unsafe.

## Storage limitation and flash wear

The tested App Lab environment does not currently allow AVM Recorder to write recordings directly to external USB storage.

Recordings are therefore written to the application's internal storage.

Observed recording profile:

- approximately `200 MB/min`;
- approximately `3–4 MB/s` sustained file writes; and
- approximately `28 Mbps` encoded video bitrate at `1280×5140`.

Automatic cleanup limits the amount of storage space retained by the application, but it does **not** eliminate cumulative writes to the underlying flash storage.

Deleting an old recording and replacing it with a new recording may keep used storage approximately constant while still generating additional writes to the underlying NAND/UFS/eMMC storage.

For example, at approximately `200 MB/min`, one hour of recording represents roughly `12 GB` of host-level video data written before accounting for filesystem or flash write amplification.

The following are not yet known for the tested head unit:

- exact internal storage device type;
- manufacturer and model;
- NAND type;
- endurance rating;
- available over-provisioning;
- wear-leveling behaviour;
- filesystem write amplification;
- lifetime write counters; and
- long-term effect of repeated recording workloads.

For this reason, **internal flash endurance and sustained storage I/O remain unresolved project risks**.

The application should avoid unnecessary temporary files and intermediate disk writes. Where possible, processing should occur in memory and only the final encoded recording should be persisted.

Protected events are excluded from normal automatic cleanup and can continue consuming storage until manually deleted.

## Recording privacy

Recordings may contain identifiable people, faces, licence plates, homes, private property, travel routes and other location or behavioural information.

Users are responsible for complying with the privacy, surveillance, recording and publication laws that apply where the vehicle is used. Do not publish or share identifiable footage without an appropriate legal basis or consent, and consider redacting faces, licence plates and other identifying details first.

Protected recordings are not removed by automatic cleanup. Review and manually delete sensitive recordings when they are no longer required.

The current alpha records video only. It does not request microphone permission or record audio.

## Camera ownership

The factory vehicle software may take ownership of the relevant camera resource for functions such as:

- the factory 360° view;
- reverse camera;
- parking camera functions; or
- other OEM camera interfaces.

AVM Recorder must not interfere with those functions.

If Android reports that the camera is unavailable or already in use:

1. stop or pause recording;
2. allow the factory vehicle function to retain the camera; and
3. retry only after the factory function has released it.

Stop using AVM Recorder if any factory camera, reverse-view, parking-view or infotainment behaviour appears abnormal.

AVM Recorder must never be treated as having priority over an OEM vehicle function.

## Installation

Once GitHub Releases can be accessed from the tested head-unit environment:

1. Open this repository's GitHub Releases page.
2. Download the signed `AVM-Recorder-v0.1.0-alpha.apk` release asset.
3. Open the downloaded APK.
4. Confirm installation through the available system/App Lab package installation flow.
5. Launch AVM Recorder.
6. Grant camera access when requested.

Do not install a file whose name ends in `-unsigned.apk`.

Unsigned files are build-validation artifacts and are not installable release packages.

Installation and App Lab behaviour may differ after vehicle software updates or on other Zeekr models, regions or head-unit configurations.

## Security and inspection

The source code is publicly visible so that users can inspect its behaviour before installation. Public visibility does not make the project open source or grant permission to reuse the code.

Users are encouraged to review:

- [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml);
- requested permissions;
- exported components;
- Gradle dependencies;
- network-related configuration;
- Camera2 usage;
- media encoding behaviour;
- storage implementation;
- Zeekr-specific assumptions; and
- cleanup and protected-recording behaviour.

The current alpha requests:

- camera permission;
- notification permission;
- foreground-service permission; and
- wake-lock permission.

It does **not** request the Android `INTERNET` permission.

Source availability does not by itself prove that the application is safe for every vehicle configuration.

Users and contributors are encouraged to report unexpected resource usage or vehicle-system interaction.

See `SECURITY.md` for reporting:

- security issues;
- privacy issues;
- excessive resource consumption;
- abnormal camera behaviour;
- unexpected vehicle-system interaction; or
- other safety-relevant observations.

Third-party component licensing is documented in `THIRD_PARTY_NOTICES.md`.

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

Without a locally configured private signing key, Gradle produces `app-release-unsigned.apk`.

Signing keys and credentials must never be committed to the repository.

## Known limitations

- Compatibility is currently confirmed only on the tested Australian Zeekr 7X App Lab environment.
- Road testing as of 12 August 2026 covers approximately four hours on one vehicle.
- The upstream source and architecture of the `1280×5140` stream have not been independently established.
- No claim is made that the AVM stream is architecturally independent of ADAS or other shared vehicle resources.
- The head unit reported approximately `9 GB` of available RAM during the tested workload, but this observation was not independently validated and does not establish long-term RAM or memory-bandwidth behaviour.
- Recordings use internal storage rather than external USB storage.
- Long-term internal flash endurance has not been established.
- Browser and dedicated-phone-app transfer are disabled in this alpha.
- The special `1280×5140` composite video may not play correctly in every third-party media player.
- Camera access can become unavailable while a factory camera function owns the relevant resource.
- The app has not completed long-term thermal, storage-wear or multi-day reliability testing.
- Hardware encoder use has not yet been verified across all compatible software versions.
- The app cannot currently establish that vehicle compute, memory, camera or storage bandwidth is unaffected.
- Compatibility may change after Zeekr OTA updates.

## Warranty, insurance and legal status

This project does not make any claim regarding vehicle warranty coverage, insurance coverage or the legal status of installing third-party applications in a particular jurisdiction.

Those questions may depend on:

- the vehicle;
- software version;
- region;
- manufacturer policy;
- insurer;
- circumstances of use; and
- whether an application or modification can be shown to have caused or contributed to a particular failure or event.

Users are responsible for checking the requirements applicable to their own vehicle and circumstances.

The project does not claim that simply installing AVM Recorder either voids or preserves any warranty or insurance coverage.

## Disclaimer

AVM Recorder is experimental, unofficial software provided for testing.

It is not affiliated with, approved by or endorsed by Zeekr.

Use it at your own risk.

Users are responsible for evaluating whether it is suitable and lawful to install and use on their own vehicle.

Successful operation on one tested vehicle does not establish safety, reliability or compatibility elsewhere.

Stop using the app if any unexpected factory camera, infotainment or vehicle behaviour occurs.

## Source availability and licence status

Copyright © 2026 Dantenothing. All rights reserved.

The source code is publicly visible for inspection and security review. No project-wide open-source licence is granted at this time, and no `LICENSE` file is included.

Except where permitted by applicable law, GitHub's Terms of Service or separate written permission, no permission is granted to copy, modify, redistribute, repackage, publish derivative versions of or sell this project's source code or APK.

The official signed APK published by this repository may be downloaded and used for personal, non-commercial evaluation and testing. This limited permission does not include modification or redistribution.

The AVM Recorder name and logo are not licensed for use in derivative branding or to imply that a modified build is an official release.

Third-party components remain governed by their own licences as described in `THIRD_PARTY_NOTICES.md`.
