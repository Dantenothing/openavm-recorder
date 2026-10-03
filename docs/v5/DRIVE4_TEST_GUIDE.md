# OpenAVM integrated drive test — 5.1.0-drive4

Install **both** APKs over the corresponding existing apps. Recorder is version code 109; Phone is 55. Do not uninstall first. The package IDs and signing identity remain the same so existing media, settings and local pairing can be retained. This is a private candidate; the public GitHub release has not changed.

This build brings optional three-camera recording, recorded GPS and vehicle observations, live and replay HUD, whole-session review, street maps, events, selected-camera clip export and a combined diagnostic report together. These local features need no Zeekr account, connection JSON, private server or map API key.

## Set up once, while parked

1. Choose **Surround → Normal**. In Settings → **Recording channels**, enable **Front cabin** and **Rear cabin** for this test. Both are normally off. Cabin video uses internal storage at 640×480 and 2 Mb/s per camera, requesting 15 fps; actual camera delivery depends on the firmware. Surround keeps your existing storage choice. Check both internal and USB free space.
2. Enable **Location & speed** and allow location permission. Separately enable **Vehicle data with recordings**. Stop the manual signal checker. These options are optional and default off; unavailable data does not invent a route or a zero reading.
3. Keep the existing floating-window/return choice you want to test. Optional channels follow the recording choice; they do not independently start just because a preview is visible. HUD can be hidden and shown in the live and playback views.

## One complete test

1. Start one normal recording and check each enabled channel. **Writing video**, advancing written-frame counts and completed files are evidence of output; **Starting** or a preview alone is not. An unavailable cabin camera should be labelled and should not stop surround recording.
2. Record across at least two file boundaries. During an ordinary drive, speed, gear, indicators and braking can be compared later in the replay. No unusual manoeuvres are required. Use a passenger for controls, or operate them when parked. Save one important moment using the existing emergency/save shortcut.
3. Stop and wait for file saving and camera release. In Library, open the session. USB surround and internal cabin files are presented together; **Manage source files** retains their separate storage actions. An unplugged USB source is explicitly unavailable. Do not delete the test files before transfer.
4. In **Session playback**, switch between available cameras without changing session time. Try **Overview**, then seek directly into another segment and back. The HUD follows the recording. Expand **Map & events** / **Drive data**; choose **Street map**, tap a recorded point or event, drag the map, and recenter. Missing camera/data intervals remain gaps.
5. Choose a short continuous range crossing a file boundary and export from one selected camera. On the car, use **Start here**, **End here**, then **Save selected range**. The phone offers its clip-export workflow. Compatible files are joined without recompressing; a keyframe may make the beginning slightly earlier. Unsupported combinations are explained instead of silently dropping footage. Exported video has no GPS attachment or burned-in HUD. GPX is a separate, explicit coordinate export.
6. Start receiving on the paired phone. From session playback, select the channels/files and **Include route & vehicle data**, then send. Leave both devices connected until complete. Quick-send from the list remains video-only. New session recordings require this updated phone app even when data is not selected; an older receiver must be updated before receiving them.
7. On the phone, open the received session and repeat camera switching, overview, cross-segment seeking, HUD, map/event seeking and a short clip export. Disconnect the car afterward: the received copy should remain usable. Turn internet off during playback; video and the local trace remain available. Street maps need connectivity and are not an offline city download.
8. With the car parked, check your selected return/overlay behaviour once. Confirm recording and preview resume only according to that choice, then stop and verify the final files. Finish with an older video and the ordinary single-camera path. Optional regression checks: cancel/retry transfer, then Trash/restore a disposable received copy.

## Send one report

After receiving the completed session and checking playback on the phone, use **Session review → Copy combined test report** in the player. The older single-video player keeps the entry under **Drive data**. Paste that JSON back into this conversation and add a short note describing anything visibly wrong.

The report combines the recorder snapshot carried with the selected data attachment, received-file/data integrity, playback observations and map outcomes. A missing recorder snapshot or untested action is labelled; it is not a pass. The snapshot covers the retained files/counters known at transfer, not a guarantee that every original file still exists. The car also has a recorder-only report entry.

The combined report excludes route coordinates, addresses, account/VIN identifiers, credentials, private paths and video. A separately exported GPX contains coordinates and is not needed for this test report.

## What the software cannot establish before your run

- Three simultaneous previews were previously observed. Sustained three-camera **encoding**, storage behaviour, heat and sleep/return with this binary still need this integrated test.
- Video uses recorded clock anchors where available, with their source reported. Callback/codec observation latency is unknown; this is not a calibrated frame-accurate measurement. Old files keep approximate timing.
- Speed can come from vehicle data or a labelled GPS fallback. Brake pressed is a binary state. Accelerator/brake-depth candidates remain **unverified raw values** until the vehicle's units and range are established; they are not percentage bars.
- Missing GPS is not reconstructed. Old files cannot acquire a historical route. Time-lapse synchronization, offline map packs and three-camera composited exports are outside this candidate.
- Online road tiles are provided by OpenFreeMap through MapLibre. Tile requests reveal the IP address and viewed area to the provider; the route file is not uploaded by this feature.

See `validation.json` for the exact builds, tests, signatures and package checks. Compiled device tests are separately identified from tests actually executed; automated checks do not stand in for your vehicle run.

---

## 中文测试说明

两端一起覆盖安装：车机 109、手机 55，不要先卸载，保留录像、设置和配对。本次为私人测试包，未改 GitHub 公开版本。本地录像、轨迹和传输不需要极氪账号、协议 JSON、私人服务器或地图密钥。

**停车后准备一次：**选择“环视 → 普通录像”，在设置“录像通道”打开前排、后排车内；两项默认关闭。车内录像保存到内部存储，每路 640×480、2 Mb/s，请求 15 帧，实际帧率由设备决定；环视沿用现有存储选择。开启“位置与速度”及定位权限，再独立开启“随录像记录车辆数据”，停止手动信号检查器。保留你想测试的回车录像/悬浮窗选项。

**一轮完整测试：**

1. 开始录像，确认各路进入“正在写入视频”，帧数递增，并产生完成文件；只有预览或“正在启动”不算录成功。
2. 正常录制跨过至少两个分段边界，记录一次保存重要时刻。不需要做特殊驾驶动作；行驶中的界面操作交给乘客，或停车后操作。
3. 停止并等待保存、释放完成。记录页应将 U 盘环视与内部车内录像合成一个行程入口；“管理源文件”保留各存储原有管理操作。
4. 两端回放都试一次切换相机、多画面、直接跳到后段再跳回来，以及 HUD、地图点和事件跳转。无录像/无数据的位置应显示缺失，不借用其他相机画面。
5. 选择跨段的短连续范围，导出所选相机的 MP4。车机通过“从此处开始 / 到此处结束 / 保存所选范围”操作。为保留关键帧，开始可能稍早，会提示；普通视频导出不附带坐标，也不把 HUD 烧进画面。GPX 需另行明确导出。
6. 手机开启接收，车机在行程播放器选择通道/文件，并勾选“附带轨迹与车辆数据”发送。等全部完成再断开。列表快捷发送仍只传视频。请两端一起更新。
7. 手机回看并重复第 4、5 步。断开车机后应仍可回看；关闭互联网后仍可看本地轨迹和视频。道路底图不是下载好的离线地图包。
8. 停车后测一次所选回车/悬浮窗模式，再确认最后文件；最后试旧视频及普通单相机流程。可选检查：中断重传、将一个可丢弃的接收副本移到回收站再恢复。

**最后只需给我一份 JSON：**手机收到完整行程、试完回放后，在播放器“行程回看 → 复制综合测试报告”复制并发回这个对话；旧单视频播放器的入口仍在“行驶数据”里。再简单描述肉眼看到的问题。报告会带车机随数据传来的摘要和手机检查结果；没测到的项目会明确标记，不算通过。默认报告不包含坐标、地址、VIN、账号、私有路径或视频，另行导出的 GPX 则包含坐标。

车速、挡位、转向和制动按已记录数据显示；过期或缺失保持未知。油门与刹车开度目前只保留“未核实原始值”，没有伪装成百分比。时间使用有来源标记的观察时钟，延迟仍未标定。三路持续编码、实际地图渲染及本次新版的真车休眠恢复，集中由这一轮实测确认。
