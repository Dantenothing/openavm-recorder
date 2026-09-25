# OpenAVM V5 · 车机录像与 Android 车辆助手

V5 把车机上的录像、悬浮后视镜，以及手机上的车况、备车、原厂哨兵自动化和录像管理，整理成一套日常使用流程。本轮作为首发公开试用，欢迎反馈具体车型与固件上的使用情况。

## 先选对安装包

| 附件 | 安装位置 | 主要用途 |
| --- | --- | --- |
| `OpenAVM-Recorder-5.0.0-arm64-v8a.apk` | 车机 | 录像、回看、悬浮后视镜 |
| `OpenAVM-Phone-5.0.0.apk` | Android 手机 | 车况、备车、原厂哨兵、桌面卡片、接收录像 |
| `SHA256SUMS.txt` | 校验文件 | 核对上面两个下载包 |

**手机不是必装项。** 只用车机录像可单独安装 Recorder；只用手机车控也不需要在车机安装 Recorder。传录像时再安装两端并配对。请选择本条 V5 发布附带的配套版本：车机 **5.0.0 / 102**，手机 **5.0.0 / 51**。

[项目介绍与安装指南](https://github.com/Dantenothing/openavm-recorder)

## 车机版

- 支持兼容环视配置下的连续采集与正常录像分段。
- 悬浮后视镜支持独立预览、四方向与四宫格、车内画面、缩放及录像控制。
- 回车后可保留 Logo、恢复完整预览，或在主动开启后自动录像。
- 按日期查找录像，同次录像分段归组，保存紧急片段。
- 安全手机配对与加密传输；整理新手帮助和六种界面语言。

## 手机版

- 查看车温、电量、位置和数据时间；提供 2×2、4×1、4×2、4×3 桌面卡片。
- 一键备车、再次点击停止，设置温度与座椅偏好、预约出发。
- 配置离家停车自动开启原厂哨兵、确认到家自动关闭，保留本应用中的当次手动选择。
- 普通刷新与车温更新分开，进度和结果显示更明确。
- 车况助手与 OpenAVM 录像管理合并；包含旧录像跳段处理和卡片添加反馈的改进。
- 内置当前澳洲版连接适配，新用户无需准备协议 JSON；提供简体中文、英文和分步新手指南。

## 旧用户先看

1. **覆盖安装，不要先卸载。** 使用本项目提供、与旧版签名一致的包。早期独立助手用户先按指南确认迁移方式。
2. 车机首次进入 V5 会更新一次方向配置：右侧驾驶位、前 1／后 2／左 3／右 4、车内 1／红外 0。停车核对，之后手动调整会保留。
3. 每次安装或更新后重新选择回车行为；自动录像默认不勾选。
4. 旧 V4 的手机连接需两端升级后重新安全配对一次，并核对完整指纹。
5. 换主力手机时，先暂停旧手机的预约和哨兵自动化，避免重复执行。

## 使用范围

车机需要兼容的 App Lab 摄像头、安装和存储能力，不是所有 Android 车机都支持。手机需 Android 8.0+，云端车控当前以澳洲版极氪 7X / AU 1.6.6 为适配基线，账号权限以原车授权为准。

普通刷新只读取车况；温度按钮可能短暂启动空调并请求停止本次临时空调。云端旧记录不会因为刷新成功就变成新测量。

回车恢复依赖仍在运行的应用，不保证离车后持续录像、系统结束后的自启动或远程看摄像头。手机控制原厂哨兵与 Recorder 录像是独立功能；后台省电和网络可能延迟自动化。历史测试中的一次录像写入异常仍在跟踪，详见项目使用指南。

这是独立、非官方项目。反馈请附应用版本、车型／固件、时间、步骤和提示文字，并遮住账号、位置、车牌与配对信息。

---

## English

**OpenAVM V5 combines vehicle recording and a floating live mirror with an Android assistant for status, preconditioning, factory Sentry automation and local recordings.** This first public release is offered for testing across supported vehicle and firmware configurations.

### Choose your APK

- **Recorder** (`OpenAVM-Recorder-5.0.0-arm64-v8a.apk`): install on the vehicle.
- **Phone** (`OpenAVM-Phone-5.0.0.apk`): install on Android.
- **SHA256SUMS.txt**: checksums for the two APKs.

The vehicle app works without a phone. Phone cloud controls work without Recorder installed. Install both only when you want recording transfers, and use the matching V5 assets in this release. No private server is required. Package versions: **Recorder 5.0.0 / 102**, **Phone 5.0.0 / 51**.

### Highlights

**Vehicle:** continuous capture across normal surround segment cuts on supported configurations; floating preview, directional and grid views; Logo/full-preview return modes with explicit optional automatic recording; grouped recordings, date filters and emergency clips; secure phone transfer and six UI languages.

**Phone:** status with timestamps; four widget sizes; tap-to-start/tap-again-to-stop preconditioning and departure schedules; factory Sentry activation away from home and optional deactivation after confirmed arrival; separate status and temperature refresh; integrated recording playback/export; improved legacy segment seeking and widget-add feedback. Current Australian connection configuration is included, with Chinese/English onboarding.

### Upgrade

Update in place with matching-signature packages; do not uninstall first. Early standalone-assistant users should follow the migration guide.

The first V5 vehicle launch applies right-hand drive; Front 1, Rear 2, Left 3, Right 4; Cabin 1, Infrared 0. Check while parked; later adjustments persist. Return preferences are reconfirmed after installation or update, with automatic recording unchecked by default.

Update both ends and pair again once when migrating from V4, comparing the full fingerprint. Pause automation on an old phone before enabling it on a new one.

### Scope

Vehicle support requires compatible App Lab camera, installation and storage access. Phone support starts at Android 8.0; cloud-control integration currently targets Australian ZEEKR 7X / AU 1.6.6 with existing account permissions.

Temperature acquisition may briefly run climate control; normal refresh does not. Data can still be an old vehicle report. Return recovery requires a running app and does not provide guaranteed parking recording, process resurrection or remote camera viewing. A historical recording-writer failure remains under investigation. Phone automation is subject to background/network delays.

Read the [project introduction and setup guide](https://github.com/Dantenothing/openavm-recorder). This is an independent, unofficial project. Include versions, vehicle/firmware, steps and the exact message when reporting an issue, and hide personal data.
