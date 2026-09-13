# Parking Sentry research — paused for V4

[Project home](../../README.md) · [使用说明](../../USER_GUIDE.zh-CN.md)

V4 does not provide custom parking Sentry capture, AI triggering or automatic recording when returning to the vehicle. Factory Sentry video browsing and transfer remain available.

## Why it is paused

In owner testing, vehicle sleep could remove USB/video availability, suspend capture or create discontinuities between departure and return. Earlier development builds also experienced a persistent third-party camera-feed stall requiring a head-unit restart. Improving AI thresholds cannot compensate for an unavailable or suspended video stream.

Later cleanup and fallback changes improved the tested behaviour, including the owner-retested extra-internal-clip fix. They do not establish reliable capture for an entire parking period. Private field diagnostics and incomplete reports are not being published as proof of continuous Sentry operation.

## Reference map

| Topic | Public source reference |
| --- | --- |
| Camera close ownership and acknowledgement | [`CaptureCloseTransaction.kt`](../../app/src/main/java/com/dante/zeekrcapabilitylab/service/recorder/CaptureCloseTransaction.kt), [`CaptureCleanupRuntime.kt`](../../app/src/main/java/com/dante/zeekrcapabilitylab/service/recorder/CaptureCleanupRuntime.kt) |
| Normal recorder and vehicle-away decisions | [`RecorderSession.kt`](../../app/src/main/java/com/dante/zeekrcapabilitylab/service/recorder/RecorderSession.kt), [`VehicleAwayStateMachine.kt`](../../app/src/main/java/com/dante/zeekrcapabilitylab/service/recorder/VehicleAwayStateMachine.kt) |
| USB direct output and recovery | [`UsbMediaStoreRecordingOutput.kt`](../../app/src/main/java/com/dante/zeekrcapabilitylab/service/recorder/UsbMediaStoreRecordingOutput.kt), [`UsbPendingRecordingRecoveryEngine.kt`](../../app/src/main/java/com/dante/zeekrcapabilitylab/service/recorder/UsbPendingRecordingRecoveryEngine.kt) |
| Historical-media support, shared interlocks and retained experimental source | [`sentry/`](../../app/src/main/java/com/dante/zeekrcapabilitylab/sentry/) |
| Regression coverage | [`recorder tests`](../../app/src/test/java/com/dante/zeekrcapabilitylab/recorder/), [`sentry tests`](../../app/src/test/java/com/dante/zeekrcapabilitylab/sentry/) |

The full pre-V4 recovery snapshot, original model/integration source, private Android fixtures, APKs and detailed research handoffs remain in the maintainer's local archive. They are not part of this public checkout or release assets. That archive includes real test material and must not be inferred to be available from the public references above.

## V4 boundaries

- All custom parking-capture build flags are false. Guard/Canary camera activities and services are not registered, and there is no home-screen capture/startup-resume integration.
- OpenCV and the AI model are not dependencies/assets of either V4 release APK. The old integrated research build is not a V4 build variant.
- Shared camera ownership/interlock protections remain active for normal recording. A timeout is not a release acknowledgement, and failed USB output must not cause an uncontrolled restart after vehicle departure.
- Historical completed OpenAVM events can still be read when present; that does not start capture or repair incomplete events in the background.

## Conditions before revisiting it

Work in an isolated research checkout. First establish observable conditions for camera, power and USB availability throughout parking, then verify continuous frames, safe shutdown, complete output and repeated recovery without a persistent camera stall. Only after that should AI-trigger quality or product reintegration be considered. Successful operation while sitting in the car is not parking acceptance.

保留研究资料不代表承诺当前车机一定可以实现全天候哨兵。V4 的功能重点是普通/延时录像、USB、手机传输和音效制作。
