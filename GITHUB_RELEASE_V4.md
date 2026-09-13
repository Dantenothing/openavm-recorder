# OpenAVM V4 — 4.0.0

V4 brings direct USB recording, an optional Android companion and sound tools together in the current stable release channel. It replaces the previous public alpha as the recommended download, with compatibility still based on targeted owner testing.

## Download

| File | Install on | Version | Size |
| --- | --- | --- | --- |
| [OpenAVM-Recorder-V4-arm64-v8a.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v4.0.0/OpenAVM-Recorder-V4-arm64-v8a.apk) | Compatible ARM64 head unit | 4.0.0 (57) | 2.24 MB |
| [OpenAVM-Companion-V4.apk](https://github.com/Dantenothing/openavm-recorder/releases/download/v4.0.0/OpenAVM-Companion-V4.apk) | Optional Android phone app | 4.0.0 (29) | 3.15 MB |

Android 8.0+ is required on both devices; the vehicle also needs compatible App Lab camera/storage access. Use the signed APKs attached below. Same-ID, same-signature installations can be upgraded in place. The phone APK is optional: the vehicle app and USB already support the main recording, library and sound-tool workflows.

[English setup guide](https://github.com/Dantenothing/openavm-recorder/blob/v4.0.0/USER_GUIDE.md) · [中文使用说明](https://github.com/Dantenothing/openavm-recorder/blob/v4.0.0/USER_GUIDE.zh-CN.md)

## Changes since the previous public release

- Direct USB video recording, internal-to-USB export, storage quotas and managed USB cleanup. Video is not staged internally first; small metadata/settings/diagnostics remain internal. Bounded internal fallback is retained when appropriate.
- Surround, Cabin and IR source choices; normal and time-lapse recording. The 2×, 5× and 10× time-lapse settings have targeted owner testing.
- Categories, grouped one-minute recording sessions, selected-segment transfer, bulk deletion of OpenAVM files and read-only factory Sentry browsing/transfer.
- Android hotspot/LAN transfer, direct USB access on the phone, trimming and individual-view export. Video opens directly into an integrated player and square single-camera footage retains its correct proportions.
- WAV lock/unlock sound creation on both devices, phone-to-vehicle USB sound transfer, and a vehicle toolbox for imported music/video plus USB sound preview/deletion.
- Six UI language choices plus follow system; matching icons and a simpler vehicle Settings page with advanced diagnostics hidden by default.
- Fixes USB loss during vehicle departure incorrectly creating an extra internal clip. The owner retested RC2 and reported no recurrence; V4 retains that recorder implementation.
- Custom parking Sentry capture, its AI model/OpenCV dependency and return-to-car automatic recording are excluded from V4. Shared camera cleanup safeguards and factory Sentry media support remain.

Some capabilities were developed across private test builds; this list describes the public V4 experience, not a claim that every item originated in this final build.

## Practical notes

- On the owner's car, normal recording commonly lasts about five minutes after leaving. It can help cover part of the gap before factory Sentry starts, but is not a fixed timer or guaranteed handover.
- A new sound may appear in the factory list only after leaving and returning, or reconnecting USB once file operations finish. OpenAVM cannot select or identify the active factory sound.
- Older test builds experienced a persistent third-party camera-feed stall requiring a head-unit restart. The owner reports no recurrence in current V4 testing; that does not establish a universal fix or guarantee factory-system independence.
- Vehicle model/firmware, USB and Android background behaviour can vary. Long-duration, full-disk and repeated hard-unplug behaviour are not exhaustively tested. This is independent, unofficial software; use its controls while parked.

For diagnostics, tap the vehicle version seven times in **Settings → About & privacy**, then open **Developer tools → Recording diagnostics** and copy JSON. Keep all parts of a split report together; no ZIP is needed. Review private information before sharing.

## Verification

Both apps were rebuilt from the public-source checkout. All 751 product/shared JVM tests passed, both release assemblies passed, and Lint reported zero errors (existing warnings remain). The release APKs retain the signing certificate used by preceding owner builds. APK integrity, signatures, version IDs, ABI contents, packaged languages, matching icons and absence of parking AI assets were checked. The owner accepted the RC2 departure fix and phone UI; the final branding/settings polish received local verification. Detailed build evidence is recorded in [DEVELOPMENT.md](https://github.com/Dantenothing/openavm-recorder/blob/v4.0.0/DEVELOPMENT.md).

`SHA256SUMS.txt` covers the two APKs and `RELEASE_NOTES.md` attached to this release.

## 中文

V4 包含车机端 `4.0.0 (57)` 和可选安卓手机端 `4.0.0 (29)`，作为当前正式发布版本。手机 APK 不是必装的：车机端配合 USB 即可完成录像、录像管理和音效制作等主要操作。

- 录像直接写 USB、旧内置录像导出、配额和本软件文件清理；保留必要的少量内部元数据及有条件的内置回退。
- 环视/Cabin/IR 选择、普通与延时录像；2×、5×、10× 已有车主针对性测试。
- 录像分类、同次一分钟分段归组、选择分段传手机，以及只读原车哨兵浏览/传输。
- 手机热点传输、直接 USB、裁剪和单视角导出；点开即播放，正方形视频比例正确。
- 两端 WAV 解闭锁音效制作、手机经车机保存至 USB，以及车机音效查看/试听/删除。
- 六种界面语言和跟随系统、统一图标、默认隐藏高级诊断。
- 离车后额外内置小录像修复已获车主复测，未再出现；正式版保留该录像实现。
- 自研停车哨兵、AI 模型和回车自动录像暂不提供；原车哨兵视频功能保留。

离车后约五分钟是车主车辆的实测表现，不是固定倒计时或保证与原车哨兵衔接。新音效有时需要离车再回车或重新连接 USB 后，原车列表才刷新。旧测试版的相机取流停滞在目前 V4 测试中未复现，不代表所有车辆或场景均已验证。

需要诊断时，在车机“设置 → 关于与隐私”连续点击版本七次，打开开发者工具中的录像诊断，直接复制 JSON 全部分段即可。请在停稳后操作，并检查公开反馈中的个人信息。
