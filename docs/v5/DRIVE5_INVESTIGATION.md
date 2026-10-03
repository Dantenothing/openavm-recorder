# Drive5 investigation — owner feedback on drive4

## Observed evidence and limits

The owner supplied an `OPENAVM_CAR_DRIVE_REPORT` for drive4 / source `592ee74`: three finalized files, no failed/provisional files, roughly 45 seconds per channel, 44 GPS samples and 178 vehicle observations. The surround runtime ended `INTERRUPTED / CAMERA_UNAVAILABLE`; all three runtime entries had cleanup pending/unconfirmed false. A zero surround sidecar observation count does not mean zero encoded frames: its runtime reported 1,326 written frames. The map result was **LOCAL / RENDERED**, not proof of online street-map rendering. Brake raw values changed; accelerator raw remained zero. These are not calibrated pedal percentages.

The owner clarified that black preview after Stop occurred **on the home page**; opening the concurrent camera probe allowed recording again. That probe waits for `CanaryCameraInterlock.normalIdle()` and cannot clear unresolved camera owners. Its success supports either an already released camera blocked by stale UI/handoff state, or cleanup finishing before the probe. The summary alone does not establish exact timing.

The ZEEKR logo appeared **on returning to the car**, which did not automatically wake on approach; pressing the door unlocked it and the center screen showed the logo instead of immediately displaying the home screen. The owner did not report a reboot occurring in the middle of an upload. This is a return/wake observation, not evidence of transfer-induced failure.

## Confirmed code defects

The home page waited eight seconds for global camera release, then disabled preview. Its effect did not subscribe to the cleanup-owner count or all stop-status changes, so a later successful release could leave the page black. Idle preview recovery now follows bounded, cancellable release observations; foreground, permissions, source and competing work are checked again before opening. Merely hiding the local preview does not wait for other active recorders to release their cameras.

Separately, `RecorderStatePublication` deliberately preserves a terminal recording error after a late real cleanup acknowledgement. `FloatingMirrorService.recorderStopped` previously required `lastError == null` to resume preview. Consequently, a safely stopped recording with an old error could reject preview indefinitely. The extracted policy's regression test reproduces that failure, without erasing the error or weakening native ownership gates. A matching, fenced service-stop callback acknowledges Stop; only the global gates establish actual camera/GL/service release. This also handles stopping before the first file, when service termination can precede native close callbacks. A recording-to-recording source switch still rejects a failed recording.

The generic media teardown budget was 8 seconds, shorter than supported continuous-backend shutdown waits. Backend-specific bounded budgets address this mismatch; camera/device/output close deadlines and unconfirmed ownership are retained. Stop-to-preview may wait longer, but immediately advances when all release conditions are satisfied. This does not claim that the owner's run actually reached a timeout: detailed close timings were absent from the supplied report.

The new session player passed arbitrary panel dimensions to video surfaces that do not internally preserve aspect ratio. The combined-session library card also replaced the existing image card with plain text. Drive5 fits the video externally and reuses the established thumbnail/card components while retaining the session clock, HUD and multi-camera controls.

## Similar competitor report, not a common-cause conclusion

[Zeekr Shortcut Issue #2](https://github.com/dts88/zeekr-shortcut-car/issues/2) describes returning after locking the car for more than five minutes, seeing the ZEEKR logo and losing the recording app. The [author's response](https://github.com/dts88/zeekr-shortcut-car/issues/2#issuecomment-5863350807) does not confirm that the app caused an OS reboot and requests logs. Closing that issue is not evidence of a linked reboot fix.

The project's [platform notes](https://github.com/dts88/zeekr-shortcut-car/blob/main/docs/zeekr-platform-notes.md) and [camera-threading change](https://github.com/dts88/zeekr-shortcut-car/commit/01560f4696e0a0dbe34670af236192f2ee21b946) discuss camera ownership, Binder stalls and ANRs. These are different from proof that video transfer causes a head-unit reboot. Its [lifecycle notes](https://github.com/dts88/zeekr-shortcut-car/blob/main/docs/lifecycle-spec.md) also record missing boot broadcasts on the platform. No direct upload-induced reboot case was found in the inspected public issues/releases/commits.

OpenAVM now collects bounded startup continuity and transfer-boundary evidence alongside existing app-exit/preview/cleanup evidence. A readable increasing Android boot count can establish a new boot. Equal counts with consistent elapsed time support a same-boot process restart. Missing, regressed or contradictory observations remain unknown. This does not identify the underlying reboot cause and does not add an automatic camera restart after boot.

## Verification boundary

The delivery receipt records automated tests, lint, APK signature, package/version and archive checks. No connected head unit was available during implementation. The focused owner check is in `DRIVE5_TEST_GUIDE.md`; public release and installation are not part of this local repair delivery.
