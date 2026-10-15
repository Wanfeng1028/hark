# screen-camera

> 把电脑屏幕上的一块区域，变成 Android App 的"摄像头"。投屏时不用拿起手机，也能扫电脑屏幕上的二维码。
> Feed a region of your desktop screen to an Android app's camera, over adb, without root.

**状态 / Status: v0.1.0 — 实验性，未经真机验证 (experimental, NOT yet tested on a real device).**
服务端已在本仓库的构建环境中编译并 dex 成功；桌面端的协议与编码逻辑有单元测试；
但"虚拟摄像头 → 微信/支付宝扫码"这条完整链路**还没有在任何真机上跑过**。详见 [未验证的部分](#未验证的部分--what-is-untested)。

这是一个**独立、原创的开源实现**，目标是复现独立开发者 1ittlecup（GitHub heruoxin）在闭源 Mac 应用
AndroMeld 中发布的「屏幕摄像头」功能的**效果**
（[X 上的发布帖，2026-10-12](https://fixupx.com/1ittlecup/status/2109489878174204119)：
"投屏 Android App 到桌面时，不用拿起手机，也可以扫屏幕上的二维码了"）。
本项目**没有**反编译或使用 AndroMeld 的任何代码，方案完全来自公开资料（见文末来源）。

---

## 它做什么

1. 通过 `adb` 把一个小服务端（dex jar）推到手机，用 `app_process` 以 **shell 身份（uid 2000）** 运行——和 scrcpy 一样，无需 root、无需安装 APK。
2. 服务端调用 Android 的 **VirtualDeviceManager（VDM）** 创建一个虚拟设备，在上面注册**虚拟摄像头**（后置 + 前置），并创建一个属于该虚拟设备的**虚拟显示屏**。
3. 目标 App（如微信、支付宝）被启动到这块虚拟显示屏上；电脑端用 `scrcpy --display-id=<id>` 显示并操控它。
4. 桌面端 Python 客户端用 `mss` 截取你框选的屏幕区域，编码成 JPEG（或 RGBA 原始帧），经 `adb forward` 的 socket 发给服务端。
5. 服务端把帧画进虚拟摄像头的输入 Surface。App 打开相机时拿到的就是电脑屏幕的画面——对准二维码即可识别。

```
 ┌──────────── 电脑 ────────────┐            ┌──────────────── 手机 (Android 15/16) ───────────────┐
 │ mss 截屏区域 → JPEG ─────────┼─adb fwd──▶ │ app_process (shell uid)                              │
 │                              │            │  ├ VirtualDevice (CDM 关联, POLICY_TYPE_CAMERA=CUSTOM) │
 │ scrcpy --display-id=N ◀──────┼────────────┤  ├ VirtualCamera ×2 ◀── Surface.lockCanvas 逐帧绘制   │
 │   (显示/操控虚拟屏上的 App)   │            │  └ VirtualDisplay N ── 微信/支付宝扫一扫 ── 读取摄像头   │
 └──────────────────────────────┘            └──────────────────────────────────────────────────────┘
```

## 原理（以及为什么这样选）

### 1. 用的是系统原生的"虚拟摄像头"，不是 Hook
Android 15（API 35）在 VDM 中加入了 `VirtualCamera`（`@SystemApi`）：虚拟设备的拥有者声明一个摄像头，
系统里的 **Virtual Camera Service**（AOSP `frameworks/av/services/camera/virtualcamera`，实现了 Camera HAL）
把它暴露给 Camera2 / CameraX / NDK / camera1。拥有者在 `onStreamConfigured` 回调里拿到一个 `Surface`，往里写帧即可；
服务内部用 EGL 纹理把它缩放渲染到 App 请求的 YUV/JPEG 输出（见该目录 README）。CTS 测试
`VirtualCameraUtils` 就是用 `Surface.lockCanvas()` 往这个 Surface 画的——本项目也这样做。

对比其它路线：
| 路线 | 免 root？ | 结论 |
|---|---|---|
| Xposed/LSPosed Hook 相机 API（VCam、VirtCam 等） | 否 | 需要 root + 框架，排除 |
| VirtualApp 式双开容器（VCamera 等） | 是 | 要把微信装进容器里跑，账号/风控问题大，排除 |
| `cameraservice` 的 camera injection（`injectCamera`，给 CDM/投屏用） | 是？ | 需要 `CAMERA_INJECT_EXTERNAL_CAMERA`（signature 级），Shell 清单没有申请它，shell 不持有，排除 |
| **VDM VirtualCamera + VDM 虚拟显示屏（本项目）** | **是** | shell 通过 `SYSTEM_SHELL` 角色持有 `CREATE_VIRTUAL_DEVICE`；Android 15+ |

### 2. 为什么 shell（adb）有权限
* `CREATE_VIRTUAL_DEVICE` 的保护级别是 `internal|role`（`core/res/AndroidManifest.xml`），普通 App 拿不到。
* 但 PermissionController 的 `roles.xml` 中，**`android.app.role.SYSTEM_SHELL` 角色**（持有者就是 `com.android.shell`）
  的权限列表里明确包含 `CREATE_VIRTUAL_DEVICE`、`ADD_TRUSTED_DISPLAY`、`ADD_ALWAYS_UNLOCKED_DISPLAY`（"Used for CTS testing"）。
  CTS 的 `FakeAssociationRule` 注释也写着 "Immediately drop the role and rely on Shell"。
* 所以在 `adb shell app_process` 里以 `com.android.shell` 身份调用 VDM 是被系统允许的——这与 scrcpy 的做法同理。
* 你可以自查：`adb shell dumpsys package com.android.shell | grep CREATE_VIRTUAL_DEVICE` 应显示 `granted=true`。

### 3. 为什么需要 CDM 关联
`VirtualDeviceManagerService#createVirtualDevice` 要求调用方包名拥有一个 **Companion Device Manager 关联**，
且（开启 persistent-device-id 后）其 device profile 必须是 `APP_STREAMING` / `NEARBY_DEVICE_STREAMING` / `AUTOMOTIVE_PROJECTION` 之一。
服务端首次运行时自动执行（与 CTS 的做法相同）：
```
cmd role set-bypassing-role-qualification true
cmd companiondevice associate 0 com.android.shell 02:53:43:41:4d:01 android.app.role.COMPANION_DEVICE_APP_STREAMING
cmd role set-bypassing-role-qualification false
```
（`AssociationRequestsProcessor` 只有在成功把包加为该 profile 对应角色的持有者后才保存关联，所以要临时绕过角色资格检查。）
`python -m screen_camera cleanup` 可以删除这个关联。

### 4. 为什么 App 必须跑在"虚拟显示屏"上
Android 15 起摄像头是 **device-aware** 的：`VirtualDeviceParams.POLICY_TYPE_CAMERA = DEVICE_POLICY_CUSTOM` 时，
**与该虚拟设备关联的 Context** 看到的是虚拟摄像头；手机主屏上的 App（默认设备）仍只看到真实摄像头。
一个 Activity 运行在某虚拟设备的虚拟显示屏上时，它就属于这个虚拟设备。
因此本项目创建一块 VDM 虚拟显示屏，把扫码 App 启动到上面，再用 scrcpy 镜像这块屏。

这与 AndroMeld 公开的信息一致：其帮助中心写明 "**Mac or browser camera and microphone in Meld Mode：Supported Android 16+ devices**"，
而 Meld Mode（融合模式）是 "opens Android apps on a separate display in individual Mac windows"。
（这只是基于公开描述的推断，AndroMeld 的真实实现未公开。）

### 5. 与 AndroMeld 的差异
* AndroMeld 是完整的投屏产品（每个 App 一个 Mac 窗口）；本项目只做"屏幕摄像头"这一件事，显示/操控交给 scrcpy。
* AndroMeld 标注 Android 16+；本项目代码按 Android 15 API 写，但 **Android 16+ 更可能可用**（见下表）。

## 支持的 Android 版本

| Android | API | 预期 | 说明 |
|---|---|---|---|
| ≤ 14 | ≤ 34 | ❌ 不支持 | 没有 `VirtualCamera` API |
| 15 | 35 | ⚠️ 视 ROM 而定 | 需要 aconfig 标志 `virtual_camera`、`virtual_camera_service_discovery` 开启，且 ROM 编译并启动了原生 `virtual_camera` 服务（`VirtualDeviceImpl#isVirtualCameraEnabled`）。很多 15 的量产 ROM 可能没开 |
| 16 | 36 | ✅ 最可能 | 上述两个 Java 标志已移除，只剩"ROM 是否包含 `virtual_camera` 服务"这一个条件 |
| 17+ | 37+ | ❓ | AOSP main 分支的 Shell 清单已不再直接列出 `CREATE_VIRTUAL_DEVICE`（权限仍可经 `SYSTEM_SHELL` 角色获得），行为可能变化 |

**最关键的前提：ROM 必须带有原生 `virtual_camera` 服务。** 运行 `python -m screen_camera doctor` 或
`adb shell service check virtual_camera` 检查。显示 `not found` 的设备无法使用本方案（也无法用任何免 root 的 VDM 方案）。
国产 ROM（MIUI/HyperOS、ColorOS、OriginOS 等）对 VDM/虚拟屏的定制较多，兼容性未知。

## 局限 / Limitations

* **只对运行在虚拟显示屏上的 App 生效。** 手机主屏上直接打开的微信看到的仍是真实摄像头。
* 若目标 App 已在主屏运行，`am start --display` 可能只是把它切到前台而不移动到虚拟屏 → 用 `--force-stop`。
* **相机权限是按设备区分的**：手机上授过权，在虚拟设备上仍需再授权。服务端会尝试用 shell 权限预授权（`grant=`，
  做法同 CTS `grantCameraPermission`）；失败时请在虚拟屏上点"允许"。
* 声明了 `android:canDisplayOnRemoteDevices="false"` 的 Activity 不能放到 VDM 显示屏上。
* 带 `FLAG_SECURE` 的窗口（部分支付/付款码页面）在非安全虚拟屏上会被系统遮挡并弹出提示；shell 没有创建安全虚拟屏所需的 `CAPTURE_SECURE_VIDEO_OUTPUT`。
* 虚拟摄像头上报 `EXTERNAL` 硬件级别，没有真实 3A/对焦/闪光灯；个别 App 可能对摄像头能力有特殊要求。
* 小区域放大会模糊：二维码在电脑屏幕上至少要有约 150–200 物理像素宽，框选时尽量只框住二维码并留一点白边。
* 方向：默认模拟真实手机（后置 sensor orientation 90°，前置 270°），服务端会预先旋转画面，使 App 里看到的是正的。如果画面歪了，用 `--facing` 或修改 `orientation_*` 参数。
* 未实现 H.264 传输（只有 JPEG 和 RGBA 原始帧）；对扫码场景 JPEG 足够。

## 未验证的部分 / What is untested

在没有真机的环境中完成，以下**均未在设备上验证**：
1. `app_process` 中通过反射构造 `VirtualDeviceManager(IVirtualDeviceManager, Context)` 并 `createVirtualDevice` 是否被各厂商 ROM 接受；
2. `cmd companiondevice associate … APP_STREAMING` 为 `com.android.shell` 创建关联在量产机上是否成功；
3. `Surface.lockCanvas` 绘制的帧（含旋转）在微信/支付宝中的实际识别效果；
4. scrcpy `--display-id` 镜像 VDM 虚拟屏、以及 `am start --display` 启动到 VDM 虚拟屏；
5. 预授权 CAMERA（反射 `PackageManager#grantRuntimePermission` + device context）；
6. 桌面端区域选择器（tkinter）和 `mss` 截屏在 Windows / macOS / Linux 上的表现（macOS 需要"屏幕录制"权限）。

已验证：服务端可用 JDK 17 + `android-35/android.jar` 编译、d8 转 dex 打包（本仓库环境，2026-10-15）；
桌面端协议编解码与一个模拟服务端的往返测试通过（`client/tests`）。

欢迎在真机上测试并反馈：请附上 `python -m screen_camera doctor` 输出和 `adb logcat -s screen-camera VirtualDeviceManagerService VirtualCamera`。

## 构建与运行 / Build & run

### 需要
* 电脑：Python 3.9+、[Android platform-tools](https://developer.android.com/tools/releases/platform-tools)（adb）、[scrcpy](https://github.com/Genymobile/scrcpy) 2.x+（推荐，用于显示虚拟屏）
* 构建服务端：JDK 17+、Android SDK 的 `platforms;android-35`（或更高）和任意 `build-tools`（提供 `d8`）
* 手机：Android 15/16，开启 USB 调试（或无线调试）并授权电脑

### 1. 构建服务端
```bash
export ANDROID_HOME=~/Android/Sdk          # macOS 常见路径: ~/Library/Android/sdk
./build_server.sh                          # → server/build/screen-camera-server.jar
# 或显式指定： ANDROID_JAR=/path/android.jar D8=/path/build-tools/35.0.0/d8 ./build_server.sh
```
Windows 可在 Git Bash / WSL 中运行该脚本。

### 2. 安装桌面端
```bash
cd client
python -m pip install -r requirements.txt   # mss, Pillow；Linux 还需 python3-tk
```

### 3. 检查手机
```bash
python -m screen_camera doctor
```

### 4. 运行
```bash
# 框选屏幕区域 → 推送并启动服务端 → 创建虚拟屏 → 在虚拟屏上打开微信 → scrcpy 显示虚拟屏
python -m screen_camera run --app com.tencent.mm --force-stop

# 支付宝
python -m screen_camera run --app com.eg.android.AlipayGphone --force-stop

# 指定区域（物理像素 x,y,w,h），不弹选择器
python -m screen_camera run --app com.tencent.mm --region 800,300,400,400
```
然后在 scrcpy 窗口里进入 App 的「扫一扫」。运行中可在终端输入：`s` 重新框选、`p` 暂停/恢复、`q` 退出。
退出时服务端进程结束，虚拟设备 / 虚拟屏 / 虚拟摄像头随之被系统回收（VDM 监听拥有者 binder 死亡）。

常用参数：`--camera-size 1280x720`、`--camera-fps 30`、`--fps 15`（截屏频率）、`--format raw`、`--display-size 1080x1920`、
`--always-unlocked`（锁屏时虚拟屏仍可用，需要 `ADD_ALWAYS_UNLOCKED_DISPLAY`）、`--no-scrcpy`、`--serial <id>`。

### 手动运行服务端（调试用）
```bash
adb push server/build/screen-camera-server.jar /data/local/tmp/
adb forward tcp:27183 localabstract:screen-camera
adb shell CLASSPATH=/data/local/tmp/screen-camera-server.jar app_process / io.github.screencamera.Server verbose=true
# 输出中的 "@display_id=N" 即虚拟屏 ID：
adb shell am start --display N -n com.tencent.mm/.ui.LauncherUI
scrcpy --display-id=N
```
服务端参数（`key=value`）：`width height fps facing=back,front orientation_back=90 orientation_front=270
display_width display_height display_dpi always_unlocked socket association_mac association_id user grant verbose`。

### 线协议
见 `server/src/main/java/io/github/screencamera/Protocol.java` 或 `client/screen_camera/protocol.py`：
握手 `"SCAM"+u8 版本`，之后每条消息 `u8 类型 | u32 长度 | 负载`（大端）。客户端发 JPEG / RGBA 帧；服务端回报摄像头流是否打开及期望尺寸（没有 App 用摄像头时客户端停止截屏，节省 CPU）。

## 目录结构
```
screen-camera/
├── README.md, LICENSE (Apache-2.0), NOTICE, .gitignore
├── build_server.sh                     # javac + d8，无需 Gradle
├── docs/RESEARCH.md                    # 调研笔记与来源
├── server/
│   ├── src/main/java/io/github/screencamera/
│   │   ├── Server.java                 # 入口（app_process）
│   │   ├── Options.java                # key=value 参数
│   │   ├── Env.java, ShellContext.java # app_process 环境 + shell 身份 Context
│   │   ├── Association.java            # CDM 关联（cmd companiondevice）
│   │   ├── VirtualCameraHost.java      # VirtualDevice + VirtualCamera + VirtualDisplay
│   │   ├── StreamRenderer.java         # 定速往摄像头 Surface 画帧（旋转+等比）
│   │   ├── FrameServer.java, FrameStore.java, Protocol.java, Shell.java, Ln.java
│   └── hidden-api/                     # @SystemApi 的编译期桩代码，不打包进 jar
└── client/
    ├── screen_camera/ (cli.py, adb.py, capture.py, selector.py, protocol.py)
    ├── tests/test_protocol.py
    └── requirements.txt, pyproject.toml
```

---

## English summary

**What:** while an Android phone is controlled from a computer over adb, an app's camera (e.g. a QR scanner in
WeChat/Alipay) receives a live region of the **desktop screen** instead of the real camera — an independent,
open-source re-creation of the *effect* of AndroMeld's "screen camera" feature. No AndroMeld code was used.

**How:** a dex jar runs under `adb shell app_process` as the shell user (scrcpy-style, no root, no APK). It creates a
CDM association for `com.android.shell` (APP_STREAMING profile, as CTS does), then a **VirtualDeviceManager** virtual
device with `POLICY_TYPE_CAMERA = DEVICE_POLICY_CUSTOM`, registers back/front **`VirtualCamera`s** (Android 15+
`@SystemApi`) and a **VDM virtual display**. The target app is launched on that display (cameras are device-aware:
only contexts on the virtual device see its cameras) and shown with `scrcpy --display-id`. The Python client grabs a
screen region with `mss`, sends JPEG/RGBA frames through `adb forward`, and the server draws them into the camera's
input Surface with `Surface.lockCanvas` (as AOSP CTS does). Shell may do this because the `SYSTEM_SHELL` role grants it
`CREATE_VIRTUAL_DEVICE` / `ADD_TRUSTED_DISPLAY`.

**Requires:** Android 16 (most likely to work) or Android 15 with the virtual-camera flags on, **and** a ROM that ships
the native `virtual_camera` service (`adb shell service check virtual_camera`). Only apps running on the virtual
display see the fake camera. `FLAG_SECURE` windows and activities with `canDisplayOnRemoteDevices=false` are blocked on
VDM displays.

**Status:** server compiles and dexes; client protocol is unit-tested against a fake server; **the end-to-end path has not
been tested on a real device yet** (see the Chinese "未验证的部分" list above).

**Build & run:** `./build_server.sh` (JDK 17 + android-35 `android.jar` + `d8`), `pip install -r client/requirements.txt`,
then `cd client && python -m screen_camera doctor` and `python -m screen_camera run --app com.tencent.mm --force-stop`.

## 来源 / Sources
* AndroMeld 发布帖（X）：https://fixupx.com/1ittlecup/status/2109489878174204119
* AndroMeld 帮助（"Mac or browser camera and microphone in Meld Mode — Supported Android 16+ devices"；Meld Mode 使用独立显示屏）：https://andromeld.catchingnow.com/help.md ，https://andromeld.catchingnow.com/zh-Hans/help.html
* AOSP Virtual Camera Service README：https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/camera/virtualcamera/README.md
* VirtualCameraConfig / VirtualCameraCallback / VirtualDeviceParams（POLICY_TYPE_CAMERA）/ VirtualDeviceManager：`frameworks/base/core/java/android/companion/virtual/`（android15-release, android16-release）
* VirtualDeviceManagerService#createVirtualDevice（关联与 profile 检查）、VirtualDeviceImpl#isVirtualCameraEnabled：`frameworks/base/services/companion/java/com/android/server/companion/virtual/`
* Shell 清单（CREATE_VIRTUAL_DEVICE 等）：`frameworks/base/packages/Shell/AndroidManifest.xml`
* SYSTEM_SHELL 角色权限：`packages/modules/Permission/PermissionController/res/xml/roles.xml`（android16-release）
* CompanionDeviceShellCommand、AssociationRequestsProcessor：`frameworks/base/services/companion/java/com/android/server/companion/`
* CTS：`cts/tests/tests/virtualdevice/camera/.../util/VirtualCameraUtils.java`（lockCanvas、grantCameraPermission）、`.../common/FakeAssociationRule.java`
* VDM 示例（虚拟摄像头 + MediaCodec 写 Surface）：https://android.googlesource.com/platform/development/+/refs/heads/main/samples/VirtualDeviceManager
* scrcpy（app_process + adb forward 模式，Apache-2.0）：https://github.com/Genymobile/scrcpy

更详细的调研笔记见 [docs/RESEARCH.md](docs/RESEARCH.md)。

## License
Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
