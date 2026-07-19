# FlipOuterUnlock / MIX Flip 外屏解锁模块

> **`master`** — UI 分支（带 Compose 设置界面） | **`main`** — 稳定分支（纯 hook 无界面）
>
> Make the MIX Flip outer screen behave like a normal phone display.
> 让 MIX Flip 外屏像普通手机屏幕一样工作。

**一句话**：LSPosed 模块，去除外屏挖孔、全屏显示、解除应用限制、自由切换输入法、解除强制 Sogou 锁定、修复输入法工具栏、伪装设备身份。

**One-liner**: LSPosed module — removes outer screen cutout, forces fullscreen, unlocks apps, frees IME choice (no forced Sogou), enables landscape keyboard, fixes Sogou toolbar, spoofs device identity.

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

LSPosed module for Xiaomi MIX Flip / MIX Flip 2 — unlock the outer display.

### Features

**Display & Fullscreen**
- Remove outer screen display cutout — clears camera hole-punch via `Display.getCutout()` zero-cutout injection
- Prevent cutout letterboxing — hooks `WindowState.isLetterboxedForDisplayCutout()` in system_server
- Force fullscreen for all apps — sets `layoutInDisplayCutoutMode=ALWAYS` on every Activity
- Fix app bounds on cold start and configuration changes

**Device Identity**
- Spoof device identity — hooks 7 detection paths: `MiuiMultiDisplayTypeInfo.isFlipDevice()`, `miui.os.Build`, `miuix.os.Build.IS_FLIP`, `DeviceUtils`, `DeviceHelper`, `MiuiConfigs`. Excludes SystemUI (lock screen) and Sogou IME (keyboard height)
- Spoof screen type — hooks MIUI's `Configuration.getScreenType()` to return 0 (EXPAND)
- Scan orientation may vary by app — some require holding the camera side down before opening

**App Management**
- Whitelist all apps for continuity — uses `ContinuityPolicyService` dump injection
- Compat config injection — `ApplicationCompatManager` → `miui.continuity.policy=5`
- Remove app launch restrictions — `InterceptActivityController.isInterceptListUnCheckFold()` → false

**IME & Input**
- Enable IME in landscape — hooks `shouldShowCurrentInput()` → true
- Suppress rotation toast
- Unlock IME choice — hooks `InputMethodManagerServiceImpl.isFlipTinyScreen()` → false, preventing forced Sogou switch on outer screen
- Sogou toolbar & clipboard fix — restores full keyboard layout on outer screen (uses DexKit)
- **Known issue**: Scan preview orientation varies by app. Some apps require holding the phone with the camera side down BEFORE opening the scan; others use their own camera logic and work regardless.

**SystemUI**
- Widget overlay disabled — 4-layer defense in fliphome process
- SystemUI-side widget suppression — hides decor window
- Notification menu fix — restores long-press menu via `isTinyScreen` scope faking
- Status bar clock hidden on outer screen
- Status bar icon expansion — shows up to 8 notification icons
- System gestures enabled — blocks fliphome InputMonitor, prevents miuihome NavStubView removal
- Always-On Display enabled on outer screen when folded (v2.3 — screen state fix)
- Front camera redirect to main back camera (v2 — dynamic LENS_FACING enumeration)
- Sub-screen double-tap + 3-finger swipe gestures (displayId fix for state=6)

### Hook Architecture

```
onSystemServerStarting (system_server):
├── DisplayStateHook          → dual-state: display=DUAL(6) + app=OPENED
├── CutoutHook.hookFramework  → Display.getCutout + Parser
├── LetterboxHook             → isLetterboxedForDisplayCutout → false
├── WhitelistHook             → ContinuityPolicyService dump
├── CompatConfigHook          → continuity.policy + PROPERTY_COMPAT
├── AppBoundsHook             → fillInsetsState + LaunchActivityItem
├── SystemServicesHook        → BoundsCompatUtils + getFullScreenValue
├── InputMethodHook           → shouldShowCurrentInput + isFlipTinyScreen
├── InterceptHook             → isInterceptListUnCheckFold
└── SubScreenGestureHook      → displayId redirect (1→0) for state=6

onPackageReady:
├── DeviceIdentityHook [* excl. SystemUI, Sogou] → isFlipDevice + 6 static fields
├── ScreenTypeHook [*]          → getScreenType → 0 (EXPAND)
├── AodHook [systemui, aod]     → v2.3: screen state fix + FlipLinkageStyleController
├── CameraHook [camera]         → v2: dynamic LENS_FACING enumeration
├── CutoutHook [systemui, aod, camera]
├── SystemUIHook [systemui]     → widget, notification, clock, icons
├── GestureHook [fliphome]      → v2: block InputMonitor → system gestures
├── LauncherHook [miui.home]    → block SpecialFDeviceGestureHelper → keep NavStubView
├── WatchOverlayHook [fliphome] → 4-layer widget defense
├── SogouInputHook [sogou]      → toolbar + clipboard (DexKit)
└── ActivityLifecycleHook [*]   → layoutInDisplayCutoutMode=ALWAYS
```

### Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

### Build

```bash
./gradlew :app:assembleDebug
```

### Release (signed)

Generate a keystore:
```bash
keytool -genkey -v -keystore flip.jks -keyalg RSA -keysize 2048 -validity 10000 -alias flip
```

Create `local.properties` (git-ignored):
```properties
androidStoreFile=flip.jks
androidStorePassword=<your-password>
androidKeyAlias=flip
androidKeyPassword=<your-password>
```

```bash
./gradlew :app:assembleRelease
```

For CI, add GitHub Secrets: `KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`.

### Credits

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — reference for LSPosed architecture, SogouHook, DexKit usage, SystemUI hooks, and hook utilities
- Reverse engineering references in `refMD/cleaned/` (decompiled MIUI framework, services, fliphome APKs)

### TODO

- **FaceUnlock** — Face unlock on outer screen (confirmed infeasible — see below)

> Face unlock communicates directly with the Face HAL daemon (`vendor.xiaomi.hardware.face@1.0-service`) via AIDL binder. The camera is opened INSIDE this native daemon process — `FaceProvider` in `services.jar` never calls `CameraManager`. LSPosed only works in Java processes and cannot hook native HAL binaries. All potential Java-layer hook points (blocking `startHalOperation`, returning null from `getHalInstance`, returning false from `isHardwareDetected`) would only disable face unlock entirely, not redirect the camera.
>
> Full chain: `FaceService → FaceProvider → FaceAuthenticationClient → session.getSession().authenticate() → [HAL daemon opens camera internally]`
>
> See `refMD/cleaned/Camera.md` §8 for the complete FlipRes-based analysis.

### Known Issues (Unfolded State)

This module is designed for MIX Flip with the **inner screen physically removed**. For users who still have the inner screen installed, two issues occur when the device is unfolded:

1. **Both screens ON simultaneously**: `DisplayStateHook` forces `DeviceStateToLayoutMap.get()` to always return state=6 (DUAL), which enables both displays. When the inner screen is physically present, it also stays active — causing extended desktop behavior and increased battery drain.

2. **Front camera always redirected**: `CameraHook` unconditionally redirects front camera → back camera. When unfolded, the front camera (inner screen camera) is physically accessible, but the hook still blocks it. Users cannot take selfies even when the phone is fully open.

If you use the module with the inner screen intact, you can disable these hooks by commenting them out in `Main.kt`:
- `DisplayStateHook` → comment out the `hookDisplayLayoutGet` call in `DisplayStateHook.kt`
- `CameraHook` → comment out in the hooks list

### License

AGPL-3.0

---

<a name="chinese"></a>
## 中文

### 功能

**显示与全屏**
- 移除挖孔：`Display.getCutout()` 零值注入 + `CutoutSpecification.Parser` 字段清零
- 防 letterboxing：`WindowState.isLetterboxedForDisplayCutout()` → false
- 全屏模式：所有 Activity 设置 `layoutInDisplayCutoutMode=ALWAYS`
- 修复冷启动与配置变更时 appBounds

**设备身份**
- 伪装设备类型：hook 7 条检测路径（`MiuiMultiDisplayTypeInfo.isFlipDevice()`、`miui.os.Build`、`miuix.os.Build.IS_FLIP`、`DeviceUtils`、`DeviceHelper`、`MiuiConfigs`）。排除 SystemUI（锁屏）和 Sogou 输入法（键盘高度）
- 伪装屏幕类型：`Configuration.getScreenType()` → 0
- 扫一扫预览方向因应用而异——部分需在打开前以摄像头侧为底

**应用管理**
- 所有应用白名单注入
- 兼容配置注入：`miui.continuity.policy=5`
- 移除应用启动拦截

**输入法**
- 横屏键盘启用 + 禁旋转提示
- 解除输入法锁定 — hook `InputMethodManagerServiceImpl.isFlipTinyScreen()` → false，阻止外屏强制切 Sogou
- Sogou 工具栏+剪贴板修复（DexKit）
- **已知问题**：扫一扫预览方向因应用而异。部分应用需在**点击扫一扫前**以靠近摄像头一侧为底才能正常显示；部分应用走自带逻辑无需调整

**SystemUI**
- Widget 覆盖层 4 层禁用
- SystemUI 侧 widget 隐藏
- 通知菜单修复
- 外屏状态栏时钟隐藏
- 通知图标扩展到 8 个
- 系统手势启用 — 阻止 fliphome InputMonitor + 防止 miuihome NavStubView 移除
- 折叠状态下外屏 AOD 启用（v2.3 — 屏幕状态修复）
- 前置摄像头重定向到主后摄（v2 — 动态 LENS_FACING 枚举）
- 外屏双击休眠 + 三指截屏手势（displayId 修复适配 state=6）

### 要求

- LSPosed（libxposed API 101+）
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

### 构建与签名

```bash
# 生成密钥
keytool -genkey -v -keystore flip.jks -keyalg RSA -keysize 2048 -validity 10000 -alias flip

# local.properties
androidStoreFile=flip.jks
androidStorePassword=<密码>
androidKeyAlias=flip
androidKeyPassword=<密码>

# 签名构建
./gradlew :app:assembleRelease
```

CI: GitHub Secrets → `KEYSTORE`(base64), `KEYSTORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`

### 致谢

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed 架构、SogouHook、DexKit、SystemUI hook 及工具类参考
- `refMD/cleaned/` — MIUI 框架及 fliphome 反编译参考文档

### 未完成

- **FaceUnlock** — 外屏人脸解锁（已确认不可行 — 详见下）

> 人脸解锁通过 AIDL binder 直接与 Face HAL daemon (`vendor.xiaomi.hardware.face@1.0-service`) 通信。摄像头在 native daemon 进程内部打开 — `services.jar` 中的 `FaceProvider` 从不调用 `CameraManager`。LSPosed 仅在 Java 进程工作，无法 hook native HAL 二进制文件。所有可能的 Java 层 hook 点（阻止 `startHalOperation`、`getHalInstance` 返回 null、`isHardwareDetected` 返回 false）只能完全禁用人脸解锁，无法重定向摄像头。
>
> 完整链: `FaceService → FaceProvider → FaceAuthenticationClient → session.getSession().authenticate() → [HAL daemon 内部打开摄像头]`
>
> 详见 `refMD/cleaned/Camera.md` §8

### 已知问题（展开状态下）

本模块设计用于**已拆除内屏**的 MIX Flip。对于仍保留内屏的用户，展开时有两个问题：

1. **双屏同时开启**：`DisplayStateHook` 强制 `DeviceStateToLayoutMap.get()` 返回 state=6 (DUAL)，使两块屏幕同时激活。内屏存在时也会保持亮屏，导致扩展桌面行为和额外耗电。

2. **前置摄像头始终被重定向**：`CameraHook` 无条件将前置→后置。展开时前置摄像头（内屏摄像头）物理上可访问，但 hook 仍会拦截，用户无法自拍。

如需在内屏完好时使用，可在 `Main.kt` 中注释对应 hook：
- `DisplayStateHook` → 在 `DisplayStateHook.kt` 中注释 `hookDisplayLayoutGet` 调用
- `CameraHook` → 在 hooks 列表中注释

### License

AGPL-3.0
