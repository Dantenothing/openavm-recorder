# OpenAVM Recorder 5.1.0-drive5 — stop recovery and playback repair

Private vehicle candidate, version code **110**. Install over the existing Recorder without uninstalling. The package and signing identity are unchanged. **Keep Phone 5.1.0-drive4 (55)**: this repair changes the car app and retains its transfer protocol. No public GitHub release is changed by this delivery.

## Changes

- Session review retains the common timeline and three-camera overview, while fitting surround into its established square presentation and ordinary video into its actual aspect ratio. Map & events starts collapsed so video has priority.
- Combined USB/internal sessions again use the established library cover, time row, size, status badges and menu. An online surround recording is preferred for the cover and initial playback; offline USB is not opened.
- Home preview now observes late camera cleanup instead of remaining off after one short wait. A manual stop may restore preview after the matching recording has **actually finished cleanup**, even if its saved outcome contains a historical error. The error remains in diagnostics. All camera, auxiliary, GL and service ownership gates still apply. An error does not automatically authorize a new recording.
- Continuous recording shutdown has a bounded budget appropriate to its encoder and writer. Only an explicit stop-to-preview handoff receives a longer maximum wait; fast cleanup resumes immediately.
- Reports now distinguish available boot evidence from an App restart, and retain a small sequence of transfer stages. A ZEEKR logo alone is not classified as a confirmed reboot. This adds observation, not boot-time camera recording or a promise of automatic process resurrection.

## Focused parked check

No road test is required for these fixes. Keep the existing recording/return preferences and use the same three-camera selection as drive4.

1. Record briefly, stop, let file saving finish, and check that the home preview / floating preview returns. Repeat start → stop three times. Do not open the camera probe in between: this tests the normal path directly. With “Keep window after stopping” disabled, closing the window remains expected behavior.
2. In Library, open the combined session. Check the familiar thumbnail card, time and menu. Check surround, both cabin cameras and Overview: circles should remain circular and cabin footage should not stretch. Expand Map & events when needed.
3. Send a completed session to the paired drive4 phone and check playback. Separately, check your usual leave/return behavior when convenient. If the ZEEKR logo returns on unlocking or the App disappears, reopen OpenAVM and copy the report before using the camera probe. The new startup comparison needs a prior observation from this build; a first install cannot reconstruct an old reboot.
4. In car playback → Map & events, use **Copy session test report**. This includes current/recent recovery diagnostics separately from the selected recording's historical data. If no recording is accessible, use the existing short recorder diagnostic report. Paste the JSON and describe the visible result.

The report excludes coordinates, video, account credentials and private paths. Boot and transfer evidence can identify a restart or interrupted stage but cannot by itself prove which component caused a system failure. Historical exit reasons may be unavailable on some firmware.

Hardware preview, overlays, storage timing and head-unit reboot behavior still require the owner check. See `validation.json` for software checks actually run for this binary.

## 中文补充

只覆盖更新车机 **drive5 / 110**，手机继续用 **drive4 / 55**，不要卸载。重点停车测试：三路录像 → 停止 → 等保存完成 → 预览恢复，连续做三次，中间不用“三路摄像头测试”救场。再看记录封面和回放比例，并传一次手机。

如果又出现黑屏或 ZEEKR 标志，重新打开后，在车机回看“地图与录像数据”里点“复制行程测试报告”，发这一份 JSON 即可。新版本会附上近期启动、传输、释放和预览诊断；它们与所选录像不是同一个时间范围，会明确标注。无需为了这次修复专门出门路测。
