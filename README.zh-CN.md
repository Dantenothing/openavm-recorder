# OpenAVM V4

[English](README.md) · [下载 V4](https://github.com/Dantenothing/openavm-recorder/releases/tag/v4.0.0) · [使用说明](USER_GUIDE.zh-CN.md) · [发布说明](GITHUB_RELEASE_V4.md)

在兼容的极氪 App Lab 车机上录像、查看和传输视频。V4 包含车机端 **OpenAVM Recorder**，以及可选的安卓手机端 **OpenAVM Companion**。

## 下载

| 应用 | 安装设备 | 版本 | 下载 |
| --- | --- | --- | --- |
| OpenAVM Recorder | 兼容的 ARM64 车机 | 4.0.0 (57) | [车机 APK，约 2.24 MB](https://github.com/Dantenothing/openavm-recorder/releases/download/v4.0.0/OpenAVM-Recorder-V4-arm64-v8a.apk) |
| OpenAVM Companion | Android 8.0 或以上手机 | 4.0.0 (29) | [可选手机 APK，约 3.15 MB](https://github.com/Dantenothing/openavm-recorder/releases/download/v4.0.0/OpenAVM-Companion-V4.apk) |

车机端同样要求 Android 8.0 或以上，并需要兼容的 App Lab 摄像头和存储访问能力，不能只凭安卓版本判断兼容性。发布页附带签名 APK 和 SHA-256 校验文件。通过车辆现有的 App Lab 安装流程安装；应用 ID 和签名相同的旧版本可以覆盖升级。

**安卓手机端不是必装的。** 车机端配合 U 盘即可录像、查看和管理视频、制作解闭锁音效。手机版增加无线传输、手机播放、视频裁剪和单视角导出的便利。

## V4 的主要功能

- **USB 直录：** 录像视频直接写入选中的可写 U 盘，无需先在车机里录完整段再搬运。设置、少量元数据和诊断仍保存在车机；USB 不可用时，在允许继续录制的情况下保留有条件的内置存储回退。
- **录像选择：** 四路环视、可选 Cabin 与 IR 摄像头，以及延时录像。2×、5×、10× 已完成车主针对性测试；摄像头可用性和输出分辨率取决于车辆。
- **录像管理：** 分类、同一次录像的一分钟文件归组、选择分段传手机、旧内置录像导出 USB、批量删除本软件录像。原车哨兵支持查看和传输，原厂 `/SentryMode/` 保持只读。
- **可选安卓手机端：** 热点/局域网配对、无线传输、点开即播放、正方形视频正确显示、裁剪和单视角导出。也支持将 U 盘直接连接手机。
- **两端音效工具：** 从支持的音乐或视频中提取、裁剪音频，制作车机使用的 WAV 并保存到 USB。车机工具箱还可查看、试听和删除 USB 音效文件。
- **多语言与界面收尾：** 英语、简体中文、繁体中文、泰语、越南语、阿拉伯语和跟随系统；两端统一图标，车机高级诊断默认隐藏。

## 使用前了解

- 在车主的车辆上，普通录像通常在离车后继续约五分钟，有助于覆盖离车到原车哨兵启动之间的一部分空档。这是**车辆实测表现**，不是固定倒计时或保证无缝衔接；V4 不控制原车哨兵的启动。
- 音效传入后，系统列表有时不会立即刷新。测试中有时需要离车再回车，或重新插拔 USB，文件才会出现。本软件无法知道原车当前选中了哪一个音效。
- **V4 不提供自研停车哨兵和回车自动录像。** 车辆休眠后的持续取帧与自动唤醒尚未达到可靠程度，相关研究暂停；原车哨兵视频浏览继续保留。详见[研究状态](research/sentry/README_V4_ARCHIVE.md)。
- 测试主要来自车主的车辆和安卓设备，不代表所有车型、固件和手机均兼容。旧测试版曾出现需要重启车机恢复的底层相机取流停滞；车主报告目前 V4 测试未复现，不等于保证不会再次发生。

这是独立开发的非官方应用，与极氪没有隶属或认可关系，也不能代替原厂安全系统或保证持续停车监控。请在停车时操作。更多背景见[平台观察与限制](PLATFORM_NOTES.zh-CN.md)及[安全问题反馈](SECURITY.md)。

## 文档

- [安装、手机配对、USB、音效与排查](USER_GUIDE.zh-CN.md)
- [English user guide](USER_GUIDE.md)
- [V4 发布说明](GITHUB_RELEASE_V4.md)和[版本记录](CHANGELOG.md)
- [构建与发布验证](DEVELOPMENT.md)
- [平台观察记录](PLATFORM_NOTES.zh-CN.md)和[暂停的哨兵研究](research/sentry/README_V4_ARCHIVE.md)

## 源代码公开与许可状态


Copyright © 2026 Dantenothing. All rights reserved.

本项目源代码公开可见，仅供检查和安全审查。目前不授予覆盖整个项目的开源许可证，仓库中也不包含 `LICENSE` 文件。

除适用法律、GitHub 服务条款或单独书面许可允许的情形外，本项目不授予复制、修改、重新分发、重新打包、发布衍生版本或销售项目源代码及 APK 的权限。

本仓库正式发布并签名的 APK 可供个人、非商业评估和测试使用。该有限许可不包括修改或重新分发 APK。

AVM Recorder 名称和 Logo 不授权用于衍生产品的品牌标识，也不得用于暗示修改版属于本项目的官方发布版本。

所有第三方组件仍分别受其自身许可证约束，详见 `THIRD_PARTY_NOTICES.md`。
