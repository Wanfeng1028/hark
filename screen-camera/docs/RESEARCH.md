# Research notes (2026-10-15)

How the approach was derived, from public sources only. AOSP files were read from
`android.googlesource.com` on the date above (branches `android14-release`, `android15-release`,
`android16-release`, `main`).

## What AndroMeld says publicly
* X post, 2026-10-12: "AndroMeld 新功能：屏幕摄像头。投屏 Android App 到桌面时，不用拿起手机，也可以扫屏幕上的二维码了。"
  https://fixupx.com/1ittlecup/status/2109489878174204119
* Help page (`/help.md`, updated 2026-09-19): requirement table row
  "Mac or browser camera and microphone in Meld Mode — Supported Android 16+ devices";
  "Meld Mode opens Android apps on a separate display in individual Mac windows";
  "On Android 14+, the standalone phone app can be activated with Wireless debugging or Shizuku … Root access is not required."
* zh-Hans help: "手机应用能用 Mac 的摄像头和麦克风吗？可以，需要支持这项功能的 Android 16 及以上设备。融合模式可以把 Mac 的摄像头和麦克风提供给手机应用。"
* Inference (not confirmed by the author): apps in Meld Mode run on a virtual display owned by a
  VirtualDeviceManager virtual device; the "screen camera" is a VDM `VirtualCamera` fed with desktop pixels,
  and the microphone is a VDM virtual audio device. "Supported devices" = ROMs shipping the `virtual_camera` service.

## Android facts the design depends on
| Fact | Where |
|---|---|
| `VirtualCamera`, `VirtualCameraConfig`, `VirtualCameraCallback` are `@SystemApi`, flag `virtual_camera`, added in API 35 | `core/java/android/companion/virtual/camera/*.java` (android15-release) |
| Stream formats: `YUV_420_888`, `RGBA_8888`; one camera per lens facing; camera policy must be CUSTOM | `VirtualCameraConfig#isFormatSupported`, `VirtualCameraController#checkConfigByPolicy` |
| Producer writes to a Surface; service binds it to an EGL texture, waits for next frame per capture request | `frameworks/av/services/camera/virtualcamera/README.md` |
| CTS draws into that Surface with `Surface.lockCanvas(null)` | `cts/tests/tests/virtualdevice/camera/.../util/VirtualCameraUtils.java` |
| `POLICY_TYPE_CAMERA` CUSTOM → contexts of the virtual device see its cameras; default device keeps real ones | `VirtualDeviceParams` javadoc |
| Virtual camera enabled iff (15) flags `virtual_camera` + `virtual_camera_service_discovery` + native build flag; (16) native build flag only | `VirtualDeviceImpl#isVirtualCameraEnabled` (android15 vs android16) |
| `createVirtualDevice` checks AttributionSource uid == calling uid, package belongs to uid, CDM association exists for package; profile ∈ {AUTOMOTIVE_PROJECTION, APP_STREAMING, NEARBY_DEVICE_STREAMING} when `persistentDeviceIdApi` | `VirtualDeviceManagerService#createVirtualDevice` |
| `cmd companiondevice associate USER PKG MAC [PROFILE]` (15), `[PROFILE] [SELF_MANAGED]` (16) | `CompanionDeviceShellCommand` |
| Association stored only after role holder added → CTS wraps the call in `setBypassingRoleQualification(true/false)` | `AssociationRequestsProcessor`, `cts/.../FakeAssociationRule.java` |
| `cmd role set-bypassing-role-qualification true|false` exists | `packages/modules/Permission/service/java/com/android/role/RoleShellCommand.java` |
| `CREATE_VIRTUAL_DEVICE` protectionLevel `internal|role` | `core/res/AndroidManifest.xml` |
| Shell manifest requests `CREATE_VIRTUAL_DEVICE`, `ADD_TRUSTED_DISPLAY` (14, 15, 16); not listed in `main` as fetched | `packages/Shell/AndroidManifest.xml` |
| `SYSTEM_SHELL` role (holder: shell) grants `CREATE_VIRTUAL_DEVICE`, `ADD_TRUSTED_DISPLAY`, `ADD_ALWAYS_UNLOCKED_DISPLAY` (minSdk 33) | `PermissionController/res/xml/roles.xml` (android16-release) |
| Camera permission is device-aware: not granted on the virtual device by default; CTS grants via `createDeviceContext(id).getPackageManager().grantRuntimePermission` | `VirtualCameraPermissionTest`, `VirtualCameraUtils#grantCameraPermission` |
| Secure windows on non-secure VDM displays are blocked with a toast | `VirtualDeviceImpl` (`vdm_secure_window`) |
| `CAMERA_INJECT_EXTERNAL_CAMERA` is `signature`; not requested by Shell | core manifest / Shell manifest |
| VDM demo host feeds a VirtualCamera by decoding H.264 with MediaCodec into the callback Surface | `development/samples/VirtualDeviceManager/host/.../RemoteCameraManager.java` |

## Rejected alternatives
* Xposed/LSPosed camera hooks (e.g. github.com/w2016561536/android_virtual_cam, github.com/wrlu/VirtCam): need root.
* Container/VirtualApp style (github.com/andvipgroup/VCamera): app must run inside a container.
* `DisplayManager.createVirtualDisplay` (scrcpy `--new-display`) alone: activities there belong to the default
  device, so they keep seeing real cameras. A VDM display is required.

## Open questions (need a device)
* Do OEM ROMs (Pixel, Samsung One UI, Xiaomi HyperOS, OPPO ColorOS, vivo OriginOS…) ship/start `virtual_camera`?
* Does `cmd companiondevice associate` with the APP_STREAMING profile succeed for `com.android.shell` on user builds?
* Does the CAMERA runtime-permission dialog render on the VDM display, or is it redirected/blocked?
* Do WeChat / Alipay scanners accept an `EXTERNAL`-level camera with fixed 3A?
